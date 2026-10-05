package io.github.dgproman.pihome.session

import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.security.GeneralSecurityException

/**
 * Encrypts the one secret this app keeps.
 *
 * An interface so that tests can run without the Android Keystore, which
 * Robolectric does not have. The only implementation that ships is
 * [KeystoreTokenCipher].
 */
interface TokenCipher {
    /** [associated] is bound to the result: decrypting with anything else fails. */
    fun encrypt(
        plaintext: ByteArray,
        associated: ByteArray,
    ): ByteArray

    /** Raises [GeneralSecurityException] for anything this did not encrypt with [associated]. */
    fun decrypt(
        ciphertext: ByteArray,
        associated: ByteArray,
    ): ByteArray
}

/**
 * AES-256-GCM under a key that lives in the Android Keystore and never leaves it.
 *
 * Tink holds no key material of its own here, so there is no keyset file to
 * lose, back up or leak: what is on disk is ciphertext, and the key that opens
 * it cannot be read out of this phone, even by this app.
 *
 * The key is made on first use. If the Keystore later loses it, as some phones
 * do after a reset of the lock screen, the stored session no longer decrypts
 * and the person is signed out rather than shown a crash.
 */
class KeystoreTokenCipher(
    private val alias: String = "pihome-session-token",
) : TokenCipher {
    private val aead: Aead by lazy {
        if (!AndroidKeystore.hasKey(alias)) {
            AndroidKeystore.generateNewAes256GcmKey(alias)
        }
        AndroidKeystore.getAead(alias)
    }

    override fun encrypt(
        plaintext: ByteArray,
        associated: ByteArray,
    ): ByteArray = aead.encrypt(plaintext, associated)

    override fun decrypt(
        ciphertext: ByteArray,
        associated: ByteArray,
    ): ByteArray = aead.decrypt(ciphertext, associated)
}
