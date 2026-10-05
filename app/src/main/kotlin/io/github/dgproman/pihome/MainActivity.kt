package io.github.dgproman.pihome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.dgproman.pihome.ui.PihomeApp
import io.github.dgproman.pihome.ui.theme.PihomeTheme

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as PihomeApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PihomeTheme {
                PihomeApp(graph, version = BuildConfig.VERSION_NAME)
            }
        }
    }

    /**
     * Each time the app comes into view, ask the hub whether the session still
     * stands. From the home network, that is also what renews it.
     */
    override fun onStart() {
        super.onStart()
        graph.gate.check()
    }
}
