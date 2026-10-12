package dev.fajar.starter.fileio

import kotlin.test.*
import kotlinx.coroutines.Job

class FileInputRegistryTest {
    @Test
    fun lateSelectionCannotReplaceOrRemoveANewerRequest() {
        val registry = FileInputRegistry<String>()
        val first = Job()
        val oldTicket = registry.reserve("request", first)
        registry.release("request")
        assertTrue(first.isCancelled)
        val next = Job()
        val newTicket = registry.reserve("request", next)
        assertFailsWith<FileInputReleasedException> {
            registry.publish("request", oldTicket, "old")
        }
        registry.discardSelection("request", oldTicket)
        registry.publish("request", newTicket, "new")
        val reader = Job()
        assertEquals("new", registry.beginRead("request", reader))
        registry.endRead("request", reader)
        assertEquals("new", registry.release("request"))
        assertTrue(next.isActive, "Releasing a selected input must not cancel its former caller")
        next.cancel()
        reader.cancel()
    }

    @Test
    fun cancellingSelectionDoesNotRetainTheCallerOrAcceptItsLateResult() {
        val registry = FileInputRegistry<String>()
        val pending = Job()
        val ticket = registry.reserve("one", pending)
        registry.cancelSelections()
        assertTrue(pending.isCancelled)
        assertFailsWith<FileInputReleasedException> { registry.publish("one", ticket, "late") }
        registry.discardSelection("one", ticket)
        val next = Job()
        val nextTicket = registry.reserve("two", next)
        registry.publish("two", nextTicket, "selected")
        registry.cancelSelections()
        assertTrue(next.isActive)
        registry.close()
        next.cancel()
    }

    @Test
    fun onePickerAndOneReaderPerInputKeepResourcesBounded() {
        val registry = FileInputRegistry<String>()
        val owner = Job()
        val ticket = registry.reserve("one", owner)
        assertFailsWith<FileInputBusyException> { registry.reserve("two", owner) }
        registry.publish("one", ticket, "one")
        val reader = Job()
        assertEquals("one", registry.beginRead("one", reader))
        assertFailsWith<FileInputBusyException> { registry.beginRead("one", Job()) }
        val unrelated = Job()
        registry.endRead("one", unrelated)
        assertFailsWith<FileInputBusyException> { registry.beginRead("one", unrelated) }
        registry.release("one")
        assertTrue(reader.isCancelled)
        assertFailsWith<FileInputReleasedException> { registry.beginRead("one", unrelated) }
        repeat(4) { index ->
            val id = index.toString()
            registry.publish(id, registry.reserve(id, owner), id)
        }
        assertFailsWith<FileInputCapacityException> { registry.reserve("five", owner) }
        assertEquals(4, registry.close().size)
        owner.cancel()
        unrelated.cancel()
    }

    @Test
    fun closingCancelsPendingWorkAndCannotBeReopened() {
        val registry = FileInputRegistry<String>()
        val selected = Job()
        registry.publish("ready", registry.reserve("ready", selected), "ready")
        val reader = Job()
        registry.beginRead("ready", reader)
        val pending = Job()
        val ticket = registry.reserve("pending", pending)
        assertEquals(listOf("ready"), registry.close())
        assertTrue(reader.isCancelled)
        assertTrue(pending.isCancelled)
        assertFailsWith<FileInputReleasedException> { registry.publish("pending", ticket, "late") }
        assertFailsWith<FileInputUnavailableException> { registry.reserve("next", selected) }
        assertEquals(emptyList(), registry.close())
        selected.cancel()
    }
}
