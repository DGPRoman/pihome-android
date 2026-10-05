package io.github.dgproman.pihome.hub

/**
 * Why a request to the hub failed.
 *
 * The web client's set, so the two clients put the same failure in the same
 * place, plus [INSECURE], which a page served by the hub itself can never meet.
 * A closed set on purpose: [worthRetrying] is an exhaustive `when`, so a kind
 * added here fails the build until somebody decides whether it deserves a
 * second attempt.
 *
 * Carries no words for a person. What the app shows is in its resources, in
 * both languages, chosen by kind.
 */
enum class HubErrorKind {
    /**
     * Nothing reached the hub: no name lookup, no connection, or a gateway in
     * front of it saying it could not reach it. A write that ends here did not
     * happen.
     */
    OFFLINE,

    /**
     * The request went out and no complete reply came back: the hub stopped
     * answering, or the connection dropped while waiting. The request may have
     * arrived, so a write may have happened, and an invitation may be spent.
     */
    TIMEOUT,

    /**
     * Plain `http://` to a name that resolves outside the home network. Refused
     * before anything was sent, because on the internet a password and a
     * session cookie in the clear are anybody's.
     */
    INSECURE,

    /** The session is not one the hub accepts, or the credentials offered were refused. */
    UNAUTHORIZED,

    /** The hub understood, and this account may not do that. */
    FORBIDDEN,

    NOT_FOUND,

    /**
     * Refused because of something already there: a name another account has,
     * or an account that is disabled and so cannot be invited.
     */
    CONFLICT,

    /** The hub refused the request this app sent: a 422, or a 4xx it does not model. */
    MALFORMED,

    /** Too many failed attempts from this address. The hub will say no for a few minutes. */
    RATE_LIMITED,

    /**
     * The hub answered with success, and the answer could not be used.
     *
     * The one kind that says a write **was applied**. Undoing an optimistic
     * switch on this would show one thing while the circuit does another.
     */
    UNREADABLE,

    /**
     * Something answered, and it was not the hub: a redirect, or a success that
     * is not JSON. A captive portal or a proxy, so a write did not happen.
     */
    NOT_THE_HUB,

    /** The hub failed while handling the request, or said it cannot right now. */
    SERVER,

    /** A fault in this app rather than anything the hub did. */
    UNEXPECTED,
    ;

    /**
     * Whether a read that failed this way is worth asking again.
     *
     * Never consulted for a write, which is sent once whatever happens: a second
     * attempt at one whose reply was lost could act twice. `UNAUTHORIZED` is
     * not retried either, because the hub counts each one against this address.
     */
    val worthRetrying: Boolean
        get() =
            when (this) {
                OFFLINE, TIMEOUT, SERVER -> true

                INSECURE, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, MALFORMED,
                RATE_LIMITED, UNREADABLE, NOT_THE_HUB, UNEXPECTED,
                -> false
            }
}

/**
 * A request to the hub that did not produce usable data.
 *
 * The only exception the client raises, apart from the cancellation of the
 * coroutine that asked, which is the caller's own and passes through untouched.
 * The message is for a log, never for the screen.
 */
class HubException(
    val kind: HubErrorKind,
    message: String,
    /** The HTTP status, or null when no response arrived at all. */
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
