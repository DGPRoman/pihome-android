package io.github.dgproman.pihome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.ExternalResource

/**
 * Runs what a ViewModel starts on the main thread under the test's own clock,
 * so a test steps through it with `runCurrent` and `advanceUntilIdle`.
 */
class MainDispatcherRule : ExternalResource() {
    override fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    override fun after() {
        Dispatchers.resetMain()
    }
}
