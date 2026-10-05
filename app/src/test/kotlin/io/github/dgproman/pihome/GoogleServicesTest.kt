package io.github.dgproman.pihome

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Holds the app to running without Google Play services.
 *
 * The app is installed from its hub, not from a store, and should work on any
 * Android phone, sending nothing to Google. Invitations are scanned with the
 * phone's own camera, so nothing here needs Google's code scanner, and this
 * fails if it, or anything else from Play services, ML Kit or Google's data
 * transport, comes back with a dependency.
 */
class GoogleServicesTest {
    @Test
    fun `nothing from Google Play services is on the app's classpath`() {
        val google =
            listOf(
                "com.google.android.gms.common.GoogleApiAvailability",
                "com.google.mlkit.common.MlKit",
                "com.google.android.datatransport.TransportFactory",
                "com.google.firebase.FirebaseApp",
            )

        val present = google.filter { runCatching { Class.forName(it, false, javaClass.classLoader) }.isSuccess }

        assertEquals(emptyList<String>(), present)
    }
}
