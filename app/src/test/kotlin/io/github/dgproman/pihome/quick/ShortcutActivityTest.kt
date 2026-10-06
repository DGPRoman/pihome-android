package io.github.dgproman.pihome.quick

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.session.HOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class ShortcutActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val porch = RelayShortcut(HOME.address.origin, "porch", "Porch light")

    @Test
    fun `a relay's shortcut carries its hub and relay to the activity`() {
        val intent = ShortcutActivity.switching(context, porch)

        assertEquals(ComponentName(context, ShortcutActivity::class.java), intent.component)
        assertEquals(ShortcutRequest.Switch(porch), ShortcutActivity.requestOf(intent))
    }

    @Test
    fun `an intent that is not a shortcut's asks for nothing`() {
        assertNull(ShortcutActivity.requestOf(Intent(context, ShortcutActivity::class.java)))
        assertNull(ShortcutActivity.requestOf(Intent(ShortcutActivity.ACTION_SWITCH).putExtra("hub", porch.hub)))
        assertNull(ShortcutActivity.requestOf(null))
    }

    @Test
    fun `the shortcuts in shortcuts xml start the activity with their own actions`() {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val shortcuts = mutableListOf<MutableMap<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "shortcut") shortcuts += mutableMapOf()
            for (i in 0 until parser.attributeCount) {
                shortcuts.lastOrNull()?.set("${parser.name}.${parser.getAttributeName(i)}", parser.getAttributeValue(i))
            }
        }

        assertEquals(
            mapOf(
                ShortcutActivity.LIGHTS_ID to ShortcutActivity.ACTION_LIGHTS,
                ShortcutActivity.ALL_OFF_ID to ShortcutActivity.ACTION_ALL_OFF,
            ),
            shortcuts.associate { it["shortcut.shortcutId"] to it["intent.action"] },
        )
        shortcuts.forEach {
            assertEquals(ShortcutActivity::class.java.name, it["intent.targetClass"])
            assertEquals(context.packageName, it["intent.targetPackage"])
        }
        assertEquals(ShortcutRequest.Lights, ShortcutActivity.requestOf(Intent(ShortcutActivity.ACTION_LIGHTS)))
        assertEquals(ShortcutRequest.AllOff, ShortcutActivity.requestOf(Intent(ShortcutActivity.ACTION_ALL_OFF)))
    }

    @Test
    fun `the activity is not exported`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, ShortcutActivity::class.java), 0)

        assertFalse(info.exported)
    }

    @Test
    fun `opened with no request, it closes at once`() {
        ActivityScenario.launch<ShortcutActivity>(Intent(context, ShortcutActivity::class.java)).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
