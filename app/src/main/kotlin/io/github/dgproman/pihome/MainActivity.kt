package io.github.dgproman.pihome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.dgproman.pihome.connect.Incoming
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.ui.PihomeApp
import io.github.dgproman.pihome.ui.theme.PihomeTheme

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as PihomeApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: a restored activity still holds the intent it was
        // opened with, and its invitation has been taken already.
        if (savedInstanceState == null) receive(intent)
        setContent {
            PihomeTheme {
                PihomeApp(graph, version = BuildConfig.VERSION_NAME)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /**
     * Each time the app comes into view, ask the hub whether the session still
     * stands. From the home network, that is also what renews it.
     */
    override fun onStart() {
        super.onStart()
        graph.gate.check()
    }

    /** An invitation the hub's join page handed over, for the screens to take. */
    private fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        graph.incoming.offer(
            when (val link = InvitationLink.parse(data)) {
                is Parsed.Valid -> Incoming.Invitation(link.value, graph.clock.instant())
                is Parsed.Invalid -> Incoming.Broken(link.problem)
            },
        )
    }
}
