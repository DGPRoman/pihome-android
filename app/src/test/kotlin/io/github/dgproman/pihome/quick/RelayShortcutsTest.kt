package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.FakeShortcutShelf
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.session.HOME
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayShortcutsTest {
    private val shelf = FakeShortcutShelf()
    private val shortcuts = RelayShortcuts(shelf)
    private val porch = Relay("porch", "Porch light", on = false)
    private val gate = Relay("gate", "Gate", on = true)
    private val hub = HOME.address.origin

    @Test
    fun `there is one shortcut per relay, in the hub's order`() {
        shortcuts.all(HOME, listOf(porch, gate))

        assertEquals(listOf(RelayShortcut(hub, "porch", "Porch light"), RelayShortcut(hub, "gate", "Gate")), shelf.shown)
        assertEquals(listOf("relay:porch", "relay:gate"), shelf.shown.map { it.id })
    }

    @Test
    fun `they follow relays being added, renamed and removed`() {
        shortcuts.all(HOME, listOf(porch))
        shortcuts.all(HOME, listOf(porch, gate))
        assertEquals(listOf("porch", "gate"), shelf.shown.map { it.relayId })

        shortcuts.all(HOME, listOf(porch.copy(label = "Porch"), gate))
        assertEquals("Porch", shelf.shown.first().name)

        shortcuts.all(HOME, listOf(gate))
        assertEquals(listOf("gate"), shelf.shown.map { it.relayId })
    }

    @Test
    fun `Android is asked only when something changed, since it limits how often`() {
        shortcuts.all(HOME, listOf(porch, gate))
        // A relay switched: its shortcut is the same.
        shortcuts.all(HOME, listOf(porch.copy(on = true), gate))
        shortcuts.all(HOME, listOf(porch, gate))

        assertEquals(1, shelf.puts)
    }

    @Test
    fun `when Android would not change them, the next list is put again`() {
        shelf.refuse = true
        shortcuts.all(HOME, listOf(porch))
        shelf.refuse = false
        shortcuts.all(HOME, listOf(porch))

        assertEquals(2, shelf.puts)
        assertEquals(listOf("porch"), shelf.shown.map { it.relayId })
    }

    @Test
    fun `a viewer, who may switch nothing, is offered no relay`() {
        shortcuts.all(HOME, listOf(porch))

        shortcuts.all(HOME.copy(session = HOME.session.copy(role = Role.VIEWER)), listOf(porch))

        assertEquals(emptyList<RelayShortcut>(), shelf.shown)
    }

    @Test
    fun `signing out takes them all away`() {
        shortcuts.all(HOME, listOf(porch, gate))

        shortcuts.clear()

        assertEquals(emptyList<RelayShortcut>(), shelf.shown)
    }
}
