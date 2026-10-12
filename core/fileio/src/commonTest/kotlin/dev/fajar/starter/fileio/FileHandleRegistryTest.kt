package dev.fajar.starter.fileio

import kotlin.test.*
import kotlinx.coroutines.Job

class FileHandleRegistryTest {
    @Test
    fun lateSelectionCannotReplaceOrRemoveANewerRequest() {
        val registry = FileHandleRegistry<String>()
        val first = Job()
        val oldTicket = registry.reserve("request", first)
        registry.release("request")
        assertTrue(first.isCancelled)
        val next = Job()
        val newTicket = registry.reserve("request", next)
        assertFailsWith<FileReleasedException> { registry.publish("request", oldTicket, "old") }
        registry.discard("request", oldTicket)
        registry.publish("request", newTicket, "new")
        val reader = Job()
        assertEquals("new", registry.beginOperation("request", reader))
        registry.endOperation("request", reader)
        assertEquals("new", registry.release("request"))
        assertTrue(next.isActive, "Releasing a selected input must not cancel its former caller")
        next.cancel()
        reader.cancel()
    }

    @Test
    fun cancellingSelectionDoesNotRetainTheCallerOrAcceptItsLateResult() {
        val registry = FileHandleRegistry<String>()
        val pending = Job()
        val ticket = registry.reserve("one", pending)
        registry.cancelAcquisitions()
        assertTrue(pending.isCancelled)
        assertFailsWith<FileReleasedException> { registry.publish("one", ticket, "late") }
        registry.discard("one", ticket)
        val next = Job()
        val nextTicket = registry.reserve("two", next)
        registry.publish("two", nextTicket, "selected")
        registry.cancelAcquisitions()
        assertTrue(next.isActive)
        registry.close()
        next.cancel()
    }

    @Test
    fun onePickerAndOneReaderPerInputKeepResourcesBounded() {
        val registry = FileHandleRegistry<String>()
        val owner = Job()
        val ticket = registry.reserve("one", owner)
        assertFailsWith<FileBusyException> { registry.reserve("two", owner) }
        registry.publish("one", ticket, "one")
        val reader = Job()
        assertEquals("one", registry.beginOperation("one", reader))
        assertFailsWith<FileBusyException> { registry.beginOperation("one", Job()) }
        val unrelated = Job()
        registry.endOperation("one", unrelated)
        assertFailsWith<FileBusyException> { registry.beginOperation("one", unrelated) }
        registry.release("one")
        assertTrue(reader.isCancelled)
        assertFailsWith<FileReleasedException> { registry.beginOperation("one", unrelated) }
        repeat(4) { index ->
            val id = index.toString()
            registry.publish(id, registry.reserve(id, owner), id)
        }
        assertFailsWith<FileCapacityException> { registry.reserve("five", owner) }
        assertEquals(4, registry.close().size)
        owner.cancel()
        unrelated.cancel()
    }

    @Test
    fun closingCancelsPendingWorkAndCannotBeReopened() {
        val registry = FileHandleRegistry<String>()
        val selected = Job()
        registry.publish("ready", registry.reserve("ready", selected), "ready")
        val reader = Job()
        registry.beginOperation("ready", reader)
        val pending = Job()
        val ticket = registry.reserve("pending", pending)
        assertEquals(listOf("ready"), registry.close())
        assertTrue(reader.isCancelled)
        assertTrue(pending.isCancelled)
        assertFailsWith<FileReleasedException> { registry.publish("pending", ticket, "late") }
        assertFailsWith<FileUnavailableException> { registry.reserve("next", selected) }
        assertEquals(emptyList(), registry.close())
        selected.cancel()
    }
}
