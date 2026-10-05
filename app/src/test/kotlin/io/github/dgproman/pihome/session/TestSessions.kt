package io.github.dgproman.pihome.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.hub.Session
import io.github.dgproman.pihome.hub.SessionToken
import io.github.dgproman.pihome.hub.SignedIn
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Instant

/**
 * Stands in for the Android Keystore, which Robolectric does not have.
 *
 * Not encryption, but it keeps the two properties the tests lean on: the token
 * does not appear in what is stored, and the associated data has to match.
 */
class FakeCipher : TokenCipher {
    /** Every decryption fails, as when the Keystore has lost its key. */
    var lostKey = false

    override fun encrypt(
        plaintext: ByteArray,
        associated: ByteArray,
    ): ByteArray = tag(associated) + plaintext.map { (it.toInt() xor 0x5a).toByte() }

    override fun decrypt(
        ciphertext: ByteArray,
        associated: ByteArray,
    ): ByteArray {
        if (lostKey) throw GeneralSecurityException("no key")
        val tag = tag(associated)
        if (!ciphertext.copyOfRange(0, tag.size).contentEquals(tag)) {
            throw GeneralSecurityException("associated data does not match")
        }
        return ciphertext.copyOfRange(tag.size, ciphertext.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    private fun tag(associated: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(associated).copyOf(8)
}

/** A session file of its own, in a folder the test throws away. */
fun TemporaryFolder.sessionFile(): File = File(root, "session.preferences_pb")

fun TemporaryFolder.sessionData(): DataStore<Preferences> = PreferenceDataStoreFactory.create { sessionFile() }

fun address(text: String): HubAddress = (HubAddress.parse(text) as Parsed.Valid).value

/** Made up: never a session any hub issued. */
val HOME =
    SavedSession(
        address = address("http://192.168.1.20:5002"),
        token = SessionToken("test-session-token"),
        session = Session("olya", Role.OPERATOR, Instant.parse("2026-11-04T12:00:00Z")),
    )

/** [HOME] as the hub hands it over when it opens. */
val HOME_OPENED = SignedIn(HOME.session, HOME.token)
