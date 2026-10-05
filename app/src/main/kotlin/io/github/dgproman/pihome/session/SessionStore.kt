package io.github.dgproman.pihome.session

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.hub.Session
import io.github.dgproman.pihome.hub.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * The hub this phone is signed in to, the token that signs it in, and who that is.
 *
 * Prints without the token, which redacts itself: a log or a crash report is no
 * place for a month of access.
 */
data class SavedSession(
    val address: HubAddress,
    val token: SessionToken,
    /** As the hub last described it: kept, so the app knows who it is before the hub answers. */
    val session: Session,
)

/** Where the session file lives. Excluded from backups by the manifest's rules, like everything. */
val Context.sessionData: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * The one session this phone holds, kept across restarts.
 *
 * The token is stored encrypted, with the hub's address bound to it as
 * associated data: a token copied next to another address does not decrypt.
 * The address and who the session belongs to are stored as they are, since
 * neither is a secret and the app shows both.
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
     * again with a new invitation. The same for anything else stored that this
     * build cannot read.
     */
    suspend fun read(): SavedSession? {
        val stored = data.data.first()
        val hub = stored[HUB] ?: return null
        val sealed = stored[TOKEN] ?: return null
        val address =
            when (val parsed = HubAddress.parse(hub)) {
                is Parsed.Valid -> parsed.value
                is Parsed.Invalid -> return forget("the saved address is not one this build takes: ${parsed.problem}")
            }
        val session =
            try {
                Session(
                    username = stored[USERNAME] ?: return forget("no name saved"),
                    role = Role.valueOf(stored[ROLE] ?: return forget("no role saved")),
                    expiresAt = Instant.parse(stored[EXPIRES_AT] ?: return forget("no expiry saved")),
                )
            } catch (e: IllegalArgumentException) {
                return forget("a role this build does not know", e)
            } catch (e: DateTimeParseException) {
                return forget("an expiry that is not a time", e)
            }
        val token =
            try {
                withContext(Dispatchers.Default) {
                    cipher.decrypt(Base64.getDecoder().decode(sealed), hub.encodeToByteArray()).decodeToString()
                }
            } catch (e: GeneralSecurityException) {
                return forget("the token does not decrypt", e)
            } catch (e: ProviderException) {
                // What a Keystore in a bad state raises on some phones, instead of the above.
                return forget("the Keystore failed", e)
            } catch (e: IllegalArgumentException) {
                // Not base64 at all: written by nothing this app ever was.
                return forget("the token is not base64", e)
            }
        if (token.isBlank()) return forget("the token is blank")
        return SavedSession(address, SessionToken(token), session)
    }

    suspend fun save(session: SavedSession) {
        // Off the main thread: the first call makes the Keystore key, which can take
        // a noticeable moment on an older phone.
        val hub = session.address.origin
        val sealed =
            withContext(Dispatchers.Default) {
                cipher.encrypt(session.token.value.encodeToByteArray(), hub.encodeToByteArray())
            }
        data.edit {
            it[HUB] = hub
            it[TOKEN] = Base64.getEncoder().encodeToString(sealed)
            it[USERNAME] = session.session.username
            it[ROLE] = session.session.role.name
            it[EXPIRES_AT] = session.session.expiresAt.toString()
        }
    }

    suspend fun clear() {
        data.edit { it.clear() }
    }

    /** Drop what cannot be read, so the question is not asked again on every start. */
    private suspend fun forget(
        why: String,
        cause: Exception? = null,
    ): SavedSession? {
        Log.w(TAG, "the saved session could not be read, so it is dropped: $why", cause)
        clear()
        return null
    }

    private companion object {
        const val TAG = "SessionStore"
        val HUB = stringPreferencesKey("hub")
        val TOKEN = stringPreferencesKey("token")
        val USERNAME = stringPreferencesKey("username")
        val ROLE = stringPreferencesKey("role")
        val EXPIRES_AT = stringPreferencesKey("expires_at")
    }
}
