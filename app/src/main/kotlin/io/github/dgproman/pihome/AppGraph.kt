package io.github.dgproman.pihome

import android.content.Context
import io.github.dgproman.pihome.session.KeystoreTokenCipher
import io.github.dgproman.pihome.session.SessionGate
import io.github.dgproman.pihome.session.SessionStore
import io.github.dgproman.pihome.session.sessionData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything the app is made of, built once and handed down.
 *
 * By hand rather than with a framework: a handful of objects, each made in one
 * place that can be read top to bottom. Tests build one with their own parts.
 */
class AppGraph(
    val sessions: SessionStore,
    /** Lives as long as the process. Work started here survives the screen that asked for it. */
    val scope: CoroutineScope,
) {
    val gate = SessionGate(sessions, scope)

    companion object {
        fun create(context: Context): AppGraph =
            AppGraph(
                sessions = SessionStore(context.sessionData, KeystoreTokenCipher()),
                // Main, so that changes to what the app shows happen in the order they
                // were asked for. Anything slow inside moves itself off it.
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
    }
}
