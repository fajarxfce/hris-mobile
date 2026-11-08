package dev.fajar.starter

import dev.fajar.starter.app.work.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationDeliveryDiagnosticsTest {
    @Test
    fun mappingFailuresRecordOnlyTheirClosedClassification() = runBlocking {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            if (event.kind == DiagnosticKind.OperationFailed) events += event
        }
        try {
            val classifications =
                mapOf(
                    "bounds" to "QueueMappingBounds",
                    "owner" to "QueueMappingOwner",
                    "invalid_record" to "QueueMappingInvalidRecord",
                    "cancelled" to "QueueMappingCancelled",
                    "staging" to "QueueMappingStaging",
                    "device" to "QueueMappingDevice",
                    "native_family" to "QueueMappingNativeFamily",
                    "room" to "QueueMappingRoom",
                    "legacy_receipt" to "QueueMappingLegacyReceipt",
                    "missing_receipt" to "QueueMappingMissingReceipt",
                    "duplicate_transaction" to "QueueMappingDuplicateTransaction",
                )
            for ((suffix, expected) in classifications) {
                events.clear()
                val failure =
                    AppResult.Failed(
                        Failure(
                            FailureKind.Conflict,
                            "private",
                            code = "client.collaboration_queue_mapping_$suffix",
                            parameters = mapOf("private" to "payload"),
                        )
                    )
                assertSame(
                    failure,
                    traceCollaborationDelivery { stage ->
                        stage(CollaborationDeliveryStage.DeliverMessages)
                        failure
                    },
                )
                assertEquals("ChatDelivery_DeliverMessages_$expected", events.single().category)
                assertFalse(encodeDiagnostic(events.single()).contains("private"))
            }
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }

    @Test
    fun deliveryFailureRecordsLastStageWithoutFreeFailureFields() = runBlocking {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            if (event.kind == DiagnosticKind.OperationFailed) events += event
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
    fun timeoutIdentifiesInterruptedStageWhileNormalCancellationStaysQuiet() = runTest {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            if (event.kind == DiagnosticKind.OperationFailed) events += event
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
                assertTrue(failure === cancelled || failure.cause === cancelled)
            }
            assertTrue(events.isEmpty())
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }
}
