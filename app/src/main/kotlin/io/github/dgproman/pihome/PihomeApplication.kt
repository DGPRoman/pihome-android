package io.github.dgproman.pihome

import android.app.Application

class PihomeApplication : Application() {
    val graph: AppGraph by lazy { AppGraph.create(this) }
}
