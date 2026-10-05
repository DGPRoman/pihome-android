package io.github.dgproman.pihome.people

import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Invitation
import io.github.dgproman.pihome.hub.Role
import java.time.Duration
import java.time.Instant

/**
 * Who can get into the house, as an admin manages it.
 *
 * Nothing here is shown before the hub has said so: unlike a relay, an account
 * is never changed on screen ahead of the hub's answer. These writes are rare
 * and deliberate, the hub may refuse one for a reason this app cannot see
 * coming, and the list decides who can open the door.
 */
data class People(
    val accounts: Section<List<Account>> = Section(),
    /** Accounts with a change of their own under way. */
    val busy: Set<String> = emptySet(),
    /** What went wrong with each account's last change, until the next one. */
    val failures: Map<String, Failure> = emptyMap(),
    /** The account an invitation is being issued to, if any. One at a time, as one is shown at a time. */
    val inviting: String? = null,
    val shown: Shown? = null,
    val adding: Adding = Adding(),
    val refreshing: Boolean = false,
)

/** A change to an account that did not go through, and which change it was. */
data class Failure(
    val action: Action,
    val kind: HubErrorKind,
)

enum class Action { ROLE, ENABLE, DISABLE, INVITE, WITHDRAW, DELETE }

/**
 * The invitation on screen, and who it is for.
 *
 * Held in memory only, by the screen's model: never saved with the screen's
 * state, never written to disk, and gone with the screen. The hub keeps only
 * its hash, so once this is gone nobody can show it again.
 */
data class Shown(
    val username: String,
    val invitation: Invitation,
    /** When the hub's answer arrived, by this phone's clock. */
    val issuedAt: Instant,
    /**
     * The hub stopped listing it before it ran out: it was used, or withdrawn or
     * replaced somewhere else. Kept once seen, because the hub stops listing one
     * that ran out too, and the read after that would otherwise turn "used"
     * into "ran out".
     */
    val gone: Boolean = false,
    val withdrawing: Boolean = false,
    val withdrawFailure: HubErrorKind? = null,
)

/** Adding a person: under way, or refused and why. */
data class Adding(
    val pending: Boolean = false,
    val failure: HubErrorKind? = null,
    /** How many have been added from here, so the form knows when to empty itself. */
    val added: Int = 0,
)

/**
 * Whether a read of the accounts says the invitation on screen stopped working
 * before its time.
 *
 * Only a read sent after the invitation was issued can say anything, since one
 * from before could not have had it; and only one that came back before it ran
 * out, since after that its absence is the expiry. Both instants are this
 * phone's clock against the hub's, so a skew between them blurs the last few
 * seconds and nothing else. The web client asks the same question the same way.
 */
fun wentAway(
    shown: Shown,
    accounts: List<Account>,
    sentAt: Instant,
    arrivedAt: Instant,
): Boolean =
    sentAt >= shown.issuedAt &&
        arrivedAt < shown.invitation.expiresAt &&
        !stillListed(accounts, shown)

/**
 * Whether the hub still lists this invitation for this account.
 *
 * Told apart from a later one issued for the same account elsewhere by when it
 * runs out, since a list never carries the token. To the second rather than
 * exactly, so two ways of writing the fraction cannot read as two invitations.
 */
private fun stillListed(
    accounts: List<Account>,
    shown: Shown,
): Boolean {
    val listed = accounts.find { it.username == shown.username }?.invitationExpiresAt ?: return false
    return Duration.between(listed, shown.invitation.expiresAt).abs() < Duration.ofSeconds(1)
}

/** How long an invitation has left. */
sealed interface TimeLeft {
    data object LessThanAMinute : TimeLeft

    /** Rounded up, so it never says a minute is left when there are two. */
    data class Minutes(
        val count: Int,
    ) : TimeLeft
}

/** How long is left until [until], or null once it has passed. */
fun timeLeft(
    until: Instant,
    now: Instant,
): TimeLeft? {
    val left = Duration.between(now, until)
    return when {
        left.isNegative || left.isZero -> null
        left < Duration.ofMinutes(1) -> TimeLeft.LessThanAMinute
        else -> TimeLeft.Minutes(((left.toMillis() + 59_999) / 60_000).toInt())
    }
}

/**
 * Whether [name] follows the hub's rule for the name an account logs in with.
 *
 * Mirrored, not owned: the hub checks every name it is given and its answer is
 * the one that counts. This only lets the form say what is wrong before a round
 * trip does. Latin only, by the hub's decision: it compares names ignoring case,
 * and folds ASCII and nothing else.
 */
fun isUsername(name: String): Boolean = name.length in 2..32 && USERNAME.matches(name)

private val USERNAME = Regex("^[a-zA-Z0-9]([a-zA-Z0-9._-]*[a-zA-Z0-9])?$")

/**
 * Whether this role manages the people who use the hub.
 *
 * Only an admin. Nobody becomes one from here, it is granted on the hub's
 * console, so there is nothing to explain to anybody else, and nothing about
 * it is shown to them.
 */
val Role.mayManagePeople: Boolean get() = this == Role.ADMIN
