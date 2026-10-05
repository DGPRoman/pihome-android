package io.github.dgproman.pihome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.dgproman.pihome.ui.PihomeApp
import io.github.dgproman.pihome.ui.theme.PihomeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as PihomeApplication).graph
        setContent {
            PihomeTheme {
                PihomeApp(graph, version = BuildConfig.VERSION_NAME)
            }
        }
    }
}
