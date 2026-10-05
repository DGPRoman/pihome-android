package io.github.dgproman.pihome.session

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

/** The hub this phone is signed in to, and the token that signs it in. */
class SavedSession(
    /** The hub's origin, such as `http://hub.local:5002`. */
    val hub: String,
    val token: String,
) {
    override fun equals(other: Any?): Boolean = other is SavedSession && other.hub == hub && other.token == token

    override fun hashCode(): Int = 31 * hub.hashCode() + token.hashCode()

    /** Never the token: a log or a crash report is no place for a month of access. */
    override fun toString(): String = "SavedSession(hub=$hub, token=redacted)"
}

/** Where the session file lives. Excluded from backups by the manifest's rules, like everything. */
val Context.sessionData: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * The one session this phone holds, kept across restarts.
 *
 * The token is stored encrypted, with the hub's address bound to it as
 * associated data: a token copied next to another address does not decrypt.
 * The address itself is stored as it is, since it is not a secret and the app
 * shows it.
 */
class SessionStore(
    private val data: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    /**
     * The saved session, or null when there is none it can use.
     *
     * A token that no longer decrypts reads as signed out, and is removed so the
     * question is not asked again on every start. That happens when the Keystore
     * has lost its key, and there is nothing to recover: the person signs in
     * again with a new invitation.
     */
    suspend fun read(): SavedSession? {
        val stored = data.data.first()
        val hub = stored[HUB] ?: return null
        val sealed = stored[TOKEN] ?: return null
        val token =
            try {
                withContext(Dispatchers.Default) {
                    cipher.decrypt(Base64.getDecoder().decode(sealed), hub.encodeToByteArray()).decodeToString()
                }
            } catch (e: GeneralSecurityException) {
                forget(e)
                return null
            } catch (e: ProviderException) {
                // What a Keystore in a bad state raises on some phones, instead of the above.
                forget(e)
                return null
            } catch (e: IllegalArgumentException) {
                // Not base64 at all: written by nothing this app ever was.
                forget(e)
                return null
            }
        return SavedSession(hub, token)
    }

    suspend fun save(session: SavedSession) {
        // Off the main thread: the first call makes the Keystore key, which can take
        // a noticeable moment on an older phone.
        val sealed =
            withContext(Dispatchers.Default) {
                cipher.encrypt(session.token.encodeToByteArray(), session.hub.encodeToByteArray())
            }
        data.edit {
            it[HUB] = session.hub
            it[TOKEN] = Base64.getEncoder().encodeToString(sealed)
        }
    }

    suspend fun clear() {
        data.edit { it.clear() }
    }

    private suspend fun forget(cause: Exception) {
        Log.w(TAG, "the saved session could not be read, so it is dropped", cause)
        clear()
    }

    private companion object {
        const val TAG = "SessionStore"
        val HUB = stringPreferencesKey("hub")
        val TOKEN = stringPreferencesKey("token")
    }
}
