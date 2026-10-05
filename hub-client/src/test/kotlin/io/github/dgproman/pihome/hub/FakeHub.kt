package io.github.dgproman.pihome.hub

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.rules.ExternalResource
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

/** A token for tests. Not one the hub ever issued. */
internal val TOKEN = SessionToken("test-session-token")

/** Short enough that a test of a deadline takes a moment rather than eight seconds. */
internal val FAST = Timeouts(connect = Duration.ofMillis(500), call = Duration.ofMillis(1500))

/** A server that stands in for the hub, answering what each test queues. */
class FakeHub : ExternalResource() {
    val server = MockWebServer()

    val address: HubAddress get() = HubAddress.parse(server.url("/").toString()).valid()

    override fun before() {
        server.start()
    }

    override fun after() {
        server.close()
    }

    fun client(
        token: SessionToken? = TOKEN,
        dns: Dns = Dns.SYSTEM,
        address: HubAddress = this.address,
    ): HubClient = HubClient(address, token, OkHttpClient.Builder().dns(dns).build(), FAST)

    /** Queue a JSON reply, or an empty one as the hub gives with a 204. */
    fun answer(
        status: Int,
        json: String? = null,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(status)
                .setHeader("Content-Type", "application/json")
                .apply { if (json != null) body(json) }
                .build(),
        )
    }

    fun answer(response: MockResponse) {
        server.enqueue(response)
    }

    fun takeRequest(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "the hub was not asked")

    val requestCount: Int get() = server.requestCount
}

/** The body a request carried, as text. */
internal val RecordedRequest.text: String get() = body?.utf8().orEmpty()
