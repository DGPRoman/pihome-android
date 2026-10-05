package io.github.dgproman.pihome.session

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val cipher = FakeCipher()
    private val data by lazy { folder.sessionData() }
    private val store by lazy { SessionStore(data, cipher) }

    @Test
    fun `nothing saved reads as signed out`() =
        runTest {
            assertNull(store.read())
        }

    @Test
    fun `a saved session reads back as it was`() =
        runTest {
            store.save(HOME)

            assertEquals(HOME, store.read())
        }

    @Test
    fun `the token is not in the file as text`() =
        runTest {
            store.save(HOME)

            val file = folder.sessionFile().readBytes().decodeToString()
            assertFalse(HOME.token.value in file)
            assertTrue(HOME.address.origin in file)
            assertTrue(HOME.session.username in file)
        }

    @Test
    fun `a token that no longer decrypts reads as signed out, and is dropped`() =
        runTest {
            store.save(HOME)
            cipher.lostKey = true

            assertNull(store.read())
            assertTrue(
                data.data
                    .first()
                    .asMap()
                    .isEmpty(),
            )
        }

    @Test
    fun `a token moved next to another hub does not decrypt`() =
        runTest {
            store.save(HOME)
            data.edit { it[stringPreferencesKey("hub")] = "http://192.168.1.50:5002" }

            assertNull(store.read())
        }

    @Test
    fun `a token that is not even base64 reads as signed out`() =
        runTest {
            data.edit {
                it[stringPreferencesKey("hub")] = HOME.address.origin
                it[stringPreferencesKey("token")] = "not base64!"
            }

            assertNull(store.read())
        }

    @Test
    fun `who the session belongs to is kept with it`() =
        runTest {
            store.save(HOME)

            assertEquals(HOME.session, store.read()?.session)
        }

    @Test
    fun `a session saved without who it belongs to reads as signed out, and is dropped`() =
        runTest {
            store.save(HOME)
            data.edit { it.remove(stringPreferencesKey("role")) }

            assertNull(store.read())
            assertTrue(
                data.data
                    .first()
                    .asMap()
                    .isEmpty(),
            )
        }

    @Test
    fun `a role this build does not know reads as signed out`() =
        runTest {
            store.save(HOME)
            data.edit { it[stringPreferencesKey("role")] = "OWNER" }

            assertNull(store.read())
        }

    @Test
    fun `an address this build no longer takes reads as signed out`() =
        runTest {
            store.save(HOME)
            data.edit { it[stringPreferencesKey("hub")] = "http://203.0.113.9:5002" }

            assertNull(store.read())
        }

    @Test
    fun `clearing forgets everything`() =
        runTest {
            store.save(HOME)
            store.clear()

            assertNull(store.read())
            assertTrue(
                data.data
                    .first()
                    .asMap()
                    .isEmpty(),
            )
        }

    @Test
    fun `a saved session never prints its token`() {
        assertFalse(HOME.token.value in HOME.toString())
    }
}
