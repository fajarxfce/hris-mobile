package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.observability.*
import org.junit.Assert.*
import org.junit.Test

class HrisProofStorageDiagnosticsTest {
    private val event = Diagnostic(DiagnosticArea.Storage, DiagnosticKind.OperationFailed)

    @Test
    fun storageContextExcludesMessagesValuesAndForeignFrames() {
        val sink = HrisProofStorageDiagnostics()
        val failure =
            IllegalStateException("private-token", IllegalArgumentException("private-body"))
        failure.stackTrace =
            arrayOf(
                StackTraceElement("dev.fajar.proof.Store", "read", "private-file", 12),
                StackTraceElement("third.party.Store", "read", "private-file", 13),
                StackTraceElement("dev.fajar.private token", "read", "private-file", 14),
            )
        sink.record(event, failure)
        val captured = sink.snapshot().single()
        assertEquals(
            listOf("IllegalStateException", "IllegalArgumentException"),
            captured.categories,
        )
        assertEquals(listOf("dev.fajar.proof.Store.read:12"), captured.frames)
        assertFalse(captured.toString().contains("private"))
        sink.record(event.copy(area = DiagnosticArea.Network), failure)
        sink.record(event, null)
        assertEquals(listOf(captured), sink.snapshot())
    }

    @Test
    fun boundedSnapshotsAreDetachedAndCyclicCausesTerminate() {
        val sink = HrisProofStorageDiagnostics()
        val first = IllegalStateException("private-first")
        val second = IllegalArgumentException("private-second", first)
        first.initCause(second)
        first.stackTrace =
            Array(20) { StackTraceElement("dev.fajar.proof.Store", "read", "private", it) }
        sink.record(event, first)
        val before = sink.snapshot()
        repeat(20) { sink.record(event, first) }
        assertEquals(1, before.size)
        assertEquals(8, sink.snapshot().size)
        assertTrue(sink.snapshot().all { it.categories.size == 4 && it.frames.size == 6 })
    }
}
