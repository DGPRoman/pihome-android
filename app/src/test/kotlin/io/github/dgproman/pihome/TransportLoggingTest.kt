package io.github.dgproman.pihome

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Holds the app to sending no usage events to Google.
 *
 * Google's data transport library, which the code scanner brings, finds where to
 * send events through backends its dependencies declare in the manifest. The app
 * removes them; this fails if a dependency, new or updated, declares one again.
 */
@RunWith(RobolectricTestRunner::class)
class TransportLoggingTest {
    @Test
    fun `no transport backend is registered, so usage events stay on the phone`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val discovery = ComponentName(context, "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery")

        val metaData = context.packageManager.getServiceInfo(discovery, PackageManager.GET_META_DATA).metaData
        val backends = metaData?.keySet().orEmpty().filter { it.startsWith("backend:") }

        assertEquals(emptyList<String>(), backends)
    }
}
