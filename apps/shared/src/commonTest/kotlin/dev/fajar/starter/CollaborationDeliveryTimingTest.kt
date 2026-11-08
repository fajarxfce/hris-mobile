package dev.fajar.starter

import dev.fajar.starter.app.work.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationDeliveryTimingTest {
    @Test
    fun recordsExclusiveStageIntervalsAndFinalResult() = runTest {
        capture { events ->
            val original = AppResult.Success(Unit)
            assertSame(
                original,
                traceCollaborationDelivery(testScheduler.timeSource) { stage ->
                    delay(15)
                    stage(CollaborationDeliveryStage.StageMessage)
                    delay(40)
                    stage(CollaborationDeliveryStage.DeliverMessages)
                    delay(25)
                    original
                },
            )
            assertEquals(
                listOf("Reconcile", "StageMessage", "DeliverMessages"),
                events.map { it.category!!.removePrefix("ChatDeliveryStage_") },
            )
            assertEquals(listOf(15L, 40L, 25L), events.map { it.durationMillis })
            assertTrue(events.all { it.kind == DiagnosticKind.OperationCompleted })
            assertTrue(events.dropLast(1).all { it.outcome == null })
            assertEquals(PerformanceOutcome.Succeeded, events.last().outcome)
            assertEquals(1, events.map { it.traceId }.distinct().size)
            assertEquals(3, events.map { it.spanId }.distinct().size)
        }
    }

    @Test
    fun repeatedMessageStagesRemainSeparateIntervals() = runTest {
        capture { events ->
            traceCollaborationDelivery(testScheduler.timeSource) { stage ->
                stage(CollaborationDeliveryStage.StageMessage)
                delay(10)
                stage(CollaborationDeliveryStage.StageMessage)
                delay(30)
                AppResult.Success(Unit)
            }
            val messages = events.filter { it.category == "ChatDeliveryStage_StageMessage" }
            assertEquals(listOf(10L, 30L), messages.map { it.durationMillis })
            assertNotEquals(messages[0].spanId, messages[1].spanId)
        }
    }

    @Test
    fun failureKeepsOriginalValueAndOnlyClosedReason() = runTest {
        capture { events ->
            val failed =
                AppResult.Failed(
                    Failure(
                        FailureKind.Storage,
                        "private body",
                        code = "private-code",
                        parameters = mapOf("token" to "private"),
                    )
                )
            assertSame(
                failed,
                traceCollaborationDelivery(testScheduler.timeSource) {
                    delay(12)
                    failed
                },
            )
            assertEquals("ChatDelivery_Reconcile_Storage", events.first().category)
            assertEquals(PerformanceOutcome.Failed, events.last().outcome)
            assertEquals(12L, events.last().durationMillis)
            assertTrue(events.none { encodeDiagnostic(it).contains("private") })
        }
    }

    @Test
    fun timeoutAndNormalCancellationFinishTheInterruptedInterval() = runTest {
        capture { events ->
            assertNull(
                withTimeoutOrNull(50) {
                    traceCollaborationDelivery(testScheduler.timeSource) { stage ->
                        delay(10)
                        stage(CollaborationDeliveryStage.EnterTimeline)
                        awaitCancellation()
                    }
                }
            )
            assertEquals(
                "ChatDelivery_EnterTimeline_Timeout",
                events.single { it.kind == DiagnosticKind.OperationFailed }.category,
            )
            assertEquals(40L, events.last().durationMillis)
            assertEquals(PerformanceOutcome.Cancelled, events.last().outcome)
            events.clear()
            val cancelled = CancellationException("private cancel")
            try {
                traceCollaborationDelivery(testScheduler.timeSource) { throw cancelled }
                fail("Cancellation was swallowed")
            } catch (actual: CancellationException) {
                // JVM coroutine stack recovery may copy an exception, retaining its exact cause.
                assertTrue(actual === cancelled || actual.cause === cancelled)
            }
            assertEquals(PerformanceOutcome.Cancelled, events.single().outcome)
            assertTrue(events.none { encodeDiagnostic(it).contains("private") })
        }
    }

    @Test
    fun thrownFailureFinishesTimingWithoutExceptionPayload() = runTest {
        capture { events ->
            val thrown = IllegalStateException("private cause")
            try {
                traceCollaborationDelivery(testScheduler.timeSource) {
                    delay(20)
                    throw thrown
                }
                fail("Failure was swallowed")
            } catch (actual: IllegalStateException) {
                assertTrue(actual === thrown || actual.cause === thrown)
            }
            assertEquals(20L, events.single().durationMillis)
            assertEquals(PerformanceOutcome.Failed, events.single().outcome)
            assertFalse(encodeDiagnostic(events.single()).contains("private"))
        }
    }

    @Test
    fun workerAndRoomCorrelateWithoutAddingNestedDurations() = runTest {
        capture { events ->
            assertEquals(
                SyncResult.Complete,
                traceCollaborationDeliveryWork(
                    CollaborationDeliveryStage.RecoverDrafts,
                    testScheduler.timeSource,
                ) { stage ->
                    delay(20)
                    stage(CollaborationDeliveryStage.DiscoverWork)
                    delay(30)
                    stage(CollaborationDeliveryStage.ProcessRooms)
                    traceCollaborationDelivery(testScheduler.timeSource) { roomStage ->
                        delay(15)
                        roomStage(CollaborationDeliveryStage.DeliverMessages)
                        delay(35)
                        AppResult.Success(Unit)
                    }
                    SyncResult.Complete
                },
            )
            assertEquals(listOf(20L, 30L, 15L, 35L, 50L), events.map { it.durationMillis })
            assertEquals(1, events.map { it.traceId }.distinct().size)
            assertEquals(events.size, events.map { it.spanId }.distinct().size)
        }
    }

    @Test
    fun retryWithoutFailureAndBlockedWorkAreNotReportedAsCompleted() = runTest {
        capture { events ->
            val retry = SyncResult.Retry()
            assertSame(
                retry,
                traceCollaborationDeliveryWork(
                    CollaborationDeliveryStage.DiscoverWork,
                    testScheduler.timeSource,
                ) {
                    retry
                },
            )
            assertEquals(PerformanceOutcome.Failed, events.single().outcome)
            events.clear()
            val blocked = SyncResult.Blocked(Failure(FailureKind.AccessDenied, "private"))
            assertSame(
                blocked,
                traceCollaborationDeliveryWork(
                    CollaborationDeliveryStage.LoadSetup,
                    testScheduler.timeSource,
                ) {
                    blocked
                },
            )
            assertEquals("ChatDelivery_LoadSetup_AccessDenied", events.first().category)
            assertEquals(PerformanceOutcome.Failed, events.last().outcome)
        }
    }

    @Test
    fun advancingIntoRecoveryDoesNotClaimSuccessfulAdmission() = runTest {
        capture { events ->
            val retry = SyncResult.Retry(Failure(FailureKind.Network, "private failure"))
            assertSame(
                retry,
                traceCollaborationDeliveryWork(
                    CollaborationDeliveryStage.LoadSetup,
                    testScheduler.timeSource,
                ) { stage ->
                    delay(10)
                    stage(CollaborationDeliveryStage.RenewWorkspace)
                    delay(20)
                    retry
                },
            )
            val intervals = events.filter { it.kind == DiagnosticKind.OperationCompleted }
            assertEquals(listOf(10L, 20L), intervals.map { it.durationMillis })
            assertNull(intervals.first().outcome)
            assertFalse(encodeDiagnostic(intervals.first()).contains("outcome"))
            assertEquals(PerformanceOutcome.Failed, intervals.last().outcome)
        }
    }

    @Test
    fun overlappingAttemptsHaveIndependentStagesAndTraces() = runTest {
        capture { events ->
            coroutineScope {
                launch {
                    traceCollaborationDelivery(testScheduler.timeSource) { stage ->
                        stage(CollaborationDeliveryStage.StageMessage)
                        delay(40)
                        AppResult.Success(Unit)
                    }
                }
                launch {
                    traceCollaborationDelivery(testScheduler.timeSource) { stage ->
                        stage(CollaborationDeliveryStage.EnterTimeline)
                        delay(15)
                        AppResult.Success(Unit)
                    }
                }
            }
            val traces = events.groupBy { it.traceId }
            assertEquals(2, traces.size)
            assertEquals(
                listOf(15L, 40L),
                traces.values.map { it.sumOf { event -> event.durationMillis!! } }.sorted(),
            )
            assertTrue(traces.values.all { it.size == 2 })
        }
    }

    private suspend fun capture(action: suspend (MutableList<Diagnostic>) -> Unit) {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            events += event
        }
        try {
            action(events)
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }
}
