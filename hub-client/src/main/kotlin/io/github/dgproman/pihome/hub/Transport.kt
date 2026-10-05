package io.github.dgproman.pihome.hub

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import java.io.IOException
import java.net.Proxy
import java.time.Duration
import kotlin.coroutines.resumeWithException

/** The cookie the hub keeps a session in. */
internal const val SESSION_COOKIE = "pihome_session"

/**
 * How long the hub is given.
 *
 * A Pi on a home network that is mid-reboot, or gone, is the common failure
 * rather than an exotic one, and without a deadline a hub that took the
 * connection and stopped answering would keep a request open for good. The
 * whole exchange gets eight seconds, as in the web client, which is shorter than
 * the interval the app polls at, so a hung read is given up before the next is
 * due. Connecting gets half of it, so a hub that is not there is reported as
 * that rather than as slow.
 */
internal data class Timeouts(
    val connect: Duration = Duration.ofSeconds(4),
    val call: Duration = Duration.ofSeconds(8),
)

internal enum class Method { GET, POST, PUT, PATCH, DELETE }

/** A reply the hub gave with success, read in full. */
internal class Reply(
    val url: HttpUrl,
    val status: Int,
    val headers: Headers,
    /** Null for a reply that by its status has none. */
    val body: String?,
)

/**
 * One request to the hub, and the rules every request follows.
 *
 * The rules are set here on a client derived from the one passed in, rather than
 * expected of it, so a caller cannot build a hub client that forgets one. The
 * derived client shares the original's connections and threads.
 */
internal class Transport(
    address: HubAddress,
    base: OkHttpClient,
    timeouts: Timeouts,
) {
    private val http: OkHttpClient =
        base
            .newBuilder()
            .connectTimeout(timeouts.connect)
            .callTimeout(timeouts.call)
            // The session is sent by hand as a header, and a header goes wherever a
            // redirect points. The hub never redirects, so one is somebody else
            // answering: a captive portal sending the cookie to its login page.
            .followRedirects(false)
            .followSslRedirects(false)
            // Straight to the hub. A proxy would make the name lookup below its
            // business, and the home-network rule would check the proxy instead.
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .eventListener(SendWatcher)
            .apply { if (address.isPlainHttp) dns(HomeNetworkDns(base.dns)) }
            .build()

    /**
     * Send one request and return the reply, or raise [HubException].
     *
     * Sends the request once. A write is never repeated here, and a POST is
     * marked so that OkHttp will not repeat it either: see [OneShotBody].
     */
    suspend fun send(
        method: Method,
        url: HttpUrl,
        body: JsonObject?,
        token: SessionToken?,
    ): Reply {
        val sent = Sent()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    // Required by the hub on a write that carries the session, so that
                    // a page on another site cannot make a browser send one. Sent on
                    // every write: the hub decides which need it, not this client.
                    if (method != Method.GET) header(CSRF_HEADER, "1")
                    token?.let { header("Cookie", "$SESSION_COOKIE=${it.value}") }
                }.method(method.name, requestBody(method, body))
                .tag(Sent::class.java, sent)
                .build()
        return http.newCall(request).await(method, sent)
    }

    private suspend fun Call.await(
        method: Method,
        sent: Sent,
    ): Reply =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(failure(method, call.request().url, e, sent))
                    }

                    // The body is read here, on OkHttp's thread and inside the call's
                    // deadline, so the caller's thread never blocks on the network.
                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resumeWith(runCatching { response.use { reply(method, it) } })
                    }
                },
            )
        }

    private companion object {
        const val CSRF_HEADER = "X-Pihome-CSRF"

        /**
         * More than the hub ever sends, by a long way. A reply that does not end is
         * not one, and holding it all would cost the phone its memory first.
         */
        const val MAX_REPLY_BYTES = 1L shl 20

        val JSON: MediaType = "application/json".toMediaType()

        fun requestBody(
            method: Method,
            body: JsonObject?,
        ): RequestBody? {
            if (method == Method.GET) return null
            val bytes = body?.toString()?.encodeToByteArray() ?: ByteArray(0)
            val type = if (body == null) null else JSON
            return if (method == Method.POST) OneShotBody(bytes, type) else bytes.toRequestBody(type)
        }

        fun reply(
            method: Method,
            response: Response,
        ): Reply {
            val status = response.code
            val where = "$method ${response.request.url.encodedPath}"
            kindFor(status, isJson(response.body))?.let { kind ->
                throw HubException(kind, "$where answered $status", status)
            }
            if (status in BODILESS) {
                return Reply(response.request.url, status, response.headers, null)
            }

            val text =
                try {
                    val source = response.body.source()
                    if (source.request(MAX_REPLY_BYTES + 1)) {
                        throw HubException(HubErrorKind.UNREADABLE, "$where sent more than $MAX_REPLY_BYTES bytes", status)
                    }
                    source.buffer.readUtf8()
                } catch (cause: IOException) {
                    // The status said success, so the hub acted; only its account of
                    // what it did is lost.
                    throw HubException(HubErrorKind.UNREADABLE, "$where: the reply broke off", status, cause)
                }
            return Reply(response.request.url, status, response.headers, text)
        }

        fun failure(
            method: Method,
            url: HttpUrl,
            cause: IOException,
            sent: Sent,
        ): HubException {
            val where = "$method ${url.encodedPath}"
            return when {
                cause.chain().any { it is PlainHttpRefusedException } -> {
                    HubException(HubErrorKind.INSECURE, "$where: ${url.host} is not on the home network", cause = cause)
                }

                sent.value -> {
                    HubException(HubErrorKind.TIMEOUT, "$where: sent, and no complete reply came", cause = cause)
                }

                else -> {
                    HubException(HubErrorKind.OFFLINE, "$where: the hub could not be reached", cause = cause)
                }
            }
        }
    }
}

/** Statuses that carry no body, so have no media type to check. */
private val BODILESS = setOf(204, 205)

/**
 * The kind of failure a status means, or null for a reply to read.
 *
 * As the web client decides it, with one difference. The hub itself answers 503,
 * as JSON, when a relay or its storage fails, and that is the hub saying "not
 * now" rather than a gateway saying it could not reach the hub, so only a 502,
 * 503 or 504 that is not JSON counts as [HubErrorKind.OFFLINE].
 */
internal fun kindFor(
    status: Int,
    json: Boolean,
): HubErrorKind? =
    when (status) {
        in 200..299 -> {
            if (status in BODILESS || json) null else HubErrorKind.NOT_THE_HUB
        }

        // The hub never redirects; see followRedirects in Transport.
        in 300..399 -> {
            HubErrorKind.NOT_THE_HUB
        }

        401 -> {
            HubErrorKind.UNAUTHORIZED
        }

        403 -> {
            HubErrorKind.FORBIDDEN
        }

        404 -> {
            HubErrorKind.NOT_FOUND
        }

        409 -> {
            HubErrorKind.CONFLICT
        }

        422 -> {
            HubErrorKind.MALFORMED
        }

        429 -> {
            HubErrorKind.RATE_LIMITED
        }

        502, 503, 504 -> {
            if (json) HubErrorKind.SERVER else HubErrorKind.OFFLINE
        }

        else -> {
            when {
                // Every reply from the hub is JSON, errors included.
                !json -> HubErrorKind.NOT_THE_HUB

                status >= 500 -> HubErrorKind.SERVER

                status >= 400 -> HubErrorKind.MALFORMED

                else -> HubErrorKind.UNEXPECTED
            }
        }
    }

/** `application/json` or any `+json` type, whatever the parameters. */
private fun isJson(body: ResponseBody): Boolean {
    val type = body.contentType() ?: return false
    return type.type == "application" && (type.subtype == "json" || type.subtype.endsWith("+json"))
}

private fun Throwable.chain(): Sequence<Throwable> =
    generateSequence(this) { it.cause }.flatMap { sequenceOf(it) + it.suppressed.asSequence() }

/** Whether this request began to go out, so may have reached the hub. */
private class Sent {
    @Volatile var value = false
}

/**
 * Marks the moment a request starts going out.
 *
 * What separates [HubErrorKind.OFFLINE], after which a write certainly did not
 * happen, from [HubErrorKind.TIMEOUT], after which it may have. The exception
 * alone cannot say: a deadline that passes while connecting and one that passes
 * while waiting for the answer arrive as the same one.
 */
private object SendWatcher : EventListener() {
    override fun requestHeadersStart(call: Call) {
        call.request().tag(Sent::class.java)?.value = true
    }
}

/**
 * A body OkHttp may send only once.
 *
 * OkHttp quietly sends a request again when the connection under it fails, if
 * it can replay the body. For the hub's POSTs that would be wrong every time: a
 * second login, a second account of the same name, a second invitation that
 * replaces the first, or an invitation redeemed twice, which spends it on the
 * first and is refused on the second. A one-shot body is what tells OkHttp not
 * to. PUT, PATCH and DELETE name the state they want, so repeating them is
 * harmless and OkHttp may.
 */
private class OneShotBody(
    private val bytes: ByteArray,
    private val type: MediaType?,
) : RequestBody() {
    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}
