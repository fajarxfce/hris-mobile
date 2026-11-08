package dev.fajar.starter.sync.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.data.repositories.PlatformSyncScheduleRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class PlatformSyncScheduleRepositoryTest {
    @Test
    fun oneRequestKeepsLazyResolutionAndCompletesAfterScheduling() = runTest {
        val events = mutableListOf<Diagnostic>()
        var resolutions = 0
        val keys = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val repository =
            PlatformSyncScheduleRepository(
                lazy {
                    resolutions++
                    object : WorkScheduler {
                        override suspend fun enqueue(key: String) {
                            keys += key
                            release.await()
                        }
                    }
                }
            )
        Diagnostics.install(DiagnosticSink { event, _ -> events += event })
        try {
            assertEquals(0, resolutions)
            val request =
                async(start = CoroutineStart.UNDISPATCHED) { repository.request("private-task") }
            assertEquals(listOf("private-task"), keys)
            assertEquals(1, resolutions)
            assertFalse(request.isCompleted)
            assertTrue(events.isEmpty())
            release.complete(Unit)
            assertEquals(AppResult.Success(Unit), request.await())
            val event = events.single()
            assertEquals(PerformanceOperation.WorkScheduling, event.operation)
            assertEquals(PerformanceOutcome.Succeeded, event.outcome)
            assertFalse(encodeDiagnostic(event).contains("private"))
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }

    @Test
    fun failureKeepsClassificationWithoutRetryingOrRecordingTaskContent() = runTest {
        val events = mutableListOf<Diagnostic>()
        var attempts = 0
        val repository =
            PlatformSyncScheduleRepository(
                lazy {
                    object : WorkScheduler {
                        override suspend fun enqueue(key: String) {
                            attempts++
                            throw IllegalStateException("private scheduler error")
                        }
                    }
                }
            )
        Diagnostics.install(DiagnosticSink { event, _ -> events += event })
        try {
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(repository.request("private-task")).failure.kind,
            )
            assertEquals(1, attempts)
            val event = events.single { it.operation == PerformanceOperation.WorkScheduling }
            assertEquals(PerformanceOutcome.Failed, event.outcome)
            assertTrue(events.none { encodeDiagnostic(it).contains("private") })
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }

    @Test
    fun interruptedSchedulingPropagatesCancellationAndFinishesTheSpanOnce() = runTest {
        val events = mutableListOf<Diagnostic>()
        var disposed = false
        val repository =
            PlatformSyncScheduleRepository(
                lazy {
                    object : WorkScheduler {
                        override suspend fun enqueue(key: String) {
                            try {
                                awaitCancellation()
                            } finally {
                                disposed = true
                            }
                        }
                    }
                }
            )
        Diagnostics.install(DiagnosticSink { event, _ -> events += event })
        try {
            val request =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.request("private-task")
                    fail("Cancelled work cannot report success")
                }
            request.cancelAndJoin()
            assertTrue(disposed)
            val event = events.single()
            assertEquals(PerformanceOperation.WorkScheduling, event.operation)
            assertEquals(PerformanceOutcome.Cancelled, event.outcome)
            assertFalse(encodeDiagnostic(event).contains("private"))
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }
}
