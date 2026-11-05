package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationOperation
import dev.fajar.starter.collaboration.data.repositories.safeAndroidCollaborationCall
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException

class AndroidCollaborationDiagnosticsTest {
    @Test
    fun sdkFailureIdentifiesOnlyTheOperationAndExceptionType() = runBlocking {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            events += event
        }
        try {
            val result =
                safeAndroidCollaborationCall(AndroidCollaborationOperation.Timeline) {
                    throw ClientException.Generic(
                        "private message and token",
                        "private SDK details",
                    )
                }
            assertEquals(FailureKind.Unexpected, (result as AppResult.Failed).failure.kind)
            assertEquals("ChatTimeline_Generic", events.single().category)
            val encoded = encodeDiagnostic(events.single())
            assertFalse(encoded.contains("private"))
            assertFalse(encoded.contains("token"))
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }

    @Test
    fun cancellationRemainsCancellationWithoutAConnectionFailure() = runBlocking {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, _ -> events += event }
        try {
            try {
                safeAndroidCollaborationCall(AndroidCollaborationOperation.HistoryWindow) {
                    throw CancellationException("cancelled")
                }
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) {
                assertTrue(events.isEmpty())
            }
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }
}
