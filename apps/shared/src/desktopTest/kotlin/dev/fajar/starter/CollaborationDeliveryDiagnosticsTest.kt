package dev.fajar.starter

import dev.fajar.starter.app.work.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlin.test.*
import kotlinx.coroutines.*

class CollaborationDeliveryDiagnosticsTest {
    @Test
    fun deliveryFailureRecordsLastStageWithoutFreeFailureFields() = runBlocking {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            events += event
        }
        try {
            val original =
                AppResult.Failed(
                    Failure(
                        FailureKind.Conflict,
                        "private-message",
                        code = "private-code",
                        parameters = mapOf("secret" to "private"),
                    )
                )
            val result = traceCollaborationDelivery { stage ->
                stage(CollaborationDeliveryStage.Flush)
                original
            }
            assertSame(original, result)
            assertEquals("ChatDelivery_Flush_Conflict", events.single().category)
            assertFalse(encodeDiagnostic(events.single()).contains("private"))
            events.clear()
            assertIs<AppResult.Success<Unit>>(
                traceCollaborationDelivery { AppResult.Success(Unit) }
            )
            assertTrue(events.isEmpty())
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }

    @Test
    fun timeoutIdentifiesInterruptedStageWhileNormalCancellationStaysQuiet() = runBlocking {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            events += event
        }
        try {
            assertNull(
                withTimeoutOrNull(10) {
                    traceCollaborationDelivery { stage ->
                        stage(CollaborationDeliveryStage.EnterTimeline)
                        awaitCancellation()
                    }
                }
            )
            assertEquals("ChatDelivery_EnterTimeline_Timeout", events.single().category)
            events.clear()
            val cancelled = CancellationException("private")
            try {
                traceCollaborationDelivery { throw cancelled }
                fail("Cancellation was swallowed")
            } catch (failure: CancellationException) {
                assertSame(cancelled, failure)
            }
            assertTrue(events.isEmpty())
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }
}
