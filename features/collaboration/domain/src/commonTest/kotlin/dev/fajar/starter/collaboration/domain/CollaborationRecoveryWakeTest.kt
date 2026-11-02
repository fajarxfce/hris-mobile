package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationRecoveryWakeTest {
    @Test
    fun failedRetentionNeverEnqueuesAndSuccessfulEnqueueNeverAcknowledges() = runTest {
        val f = WakeFixture()
        f.writeFailure = f.storage
        assertEquals(AppResult.Failed(f.storage), f.request())
        assertEquals(listOf("write"), f.calls)
        f.writeFailure = null
        assertEquals(AppResult.Success(Unit), f.request())
        assertEquals(listOf("write", "write", "schedule"), f.calls)
        assertEquals(CollaborationRecoveryWake("generation-1"), f.retained)
        assertEquals(listOf(CollaborationRecoveryWake.WORK_KEY), f.scheduled)
    }

    @Test
    fun platformFailureCanBeResumedFromTheOriginalCommittedGeneration() = runTest {
        val f = WakeFixture()
        f.scheduleResult = AppResult.Failed(f.unavailable)
        assertEquals(AppResult.Failed(f.unavailable), f.request())
        val original = f.retained
        f.scheduleResult = AppResult.Success(Unit)
        val resumed = ResumeCollaborationRecovery(f.wakes, f.schedules)
        assertEquals(AppResult.Success(true), resumed())
        assertEquals(original, f.retained)
        assertEquals(1, f.writes)
        assertEquals(listOf("write", "schedule", "read", "schedule"), f.calls)
    }

    @Test
    fun schedulingTimeoutRetainsTheWakeAndDoesNotLeaveTheSchedulerRunning() = runTest {
        val f = WakeFixture()
        var released = false
        f.onSchedule = {
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val failed = assertIs<AppResult.Failed>(f.request())
        assertEquals(FailureKind.Timeout, failed.failure.kind)
        assertEquals("client.collaboration_recovery_schedule_timeout", failed.failure.code)
        assertNotNull(f.retained)
        assertTrue(released)
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test
    fun cancellationAfterTheDurableCommitCannotEnqueueOrClearIt() = runTest {
        val f = WakeFixture()
        f.afterWrite = { currentCoroutineContext().cancel() }
        val job = launch {
            f.request()
            error("Cancelled publication")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNotNull(f.retained)
        assertEquals(listOf("write"), f.calls)
        f.afterWrite = {}
        assertEquals(AppResult.Success(true), f.resume())
        assertEquals(1, f.writes)
    }

    @Test
    fun cancellationWhileSchedulingLeavesARepairableCheckpoint() = runTest {
        val f = WakeFixture()
        val entered = CompletableDeferred<Unit>()
        f.onSchedule = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.request() }
        entered.await()
        job.cancelAndJoin()
        assertNotNull(f.retained)
        f.onSchedule = {}
        assertEquals(AppResult.Success(true), f.resume())
        assertEquals(1, f.writes)
    }

    @Test
    fun pendingReadFailuresAndAbsentWakesDoNotInventScheduling() = runTest {
        val f = WakeFixture()
        f.readFailure = f.storage
        assertEquals(AppResult.Failed(f.storage), f.resume())
        assertEquals(AppResult.Failed(f.storage), f.read())
        f.readFailure = null
        assertEquals(AppResult.Success(false), f.resume())
        assertEquals(AppResult.Success(null), f.read())
        assertTrue(f.scheduled.isEmpty())
        assertEquals(0, f.writes)
    }

    @Test
    fun lateOldCompletionCannotEraseACompetingWakeOrReplaceItsGeneration() = runTest {
        val f = WakeFixture()
        f.request()
        val first = assertIs<AppResult.Success<CollaborationRecoveryWake?>>(f.read()).value!!
        f.request()
        val next = f.retained!!
        assertNotEquals(first, next)
        assertEquals(AppResult.Success(false), f.complete(first))
        assertEquals(AppResult.Success(next), f.read())
        assertEquals(AppResult.Success(true), f.complete(next))
        assertEquals(AppResult.Success(false), f.complete(next))
        assertEquals(AppResult.Success(false), f.resume())
        assertEquals(2, f.scheduled.size)
    }

    @Test
    fun failedAcknowledgmentRetainsRecoveryAndPostCommitCancellationDoesNotUndoIt() = runTest {
        val f = WakeFixture()
        f.request()
        val wake = f.retained!!
        f.ackFailure = f.storage
        assertEquals(AppResult.Failed(f.storage), f.complete(wake))
        assertEquals(wake, f.retained)
        f.ackFailure = null
        f.afterAck = { currentCoroutineContext().cancel() }
        val job = launch {
            f.complete(wake)
            error("Cancelled acknowledgment")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(f.retained)
        assertEquals(AppResult.Success(false), f.resume())
    }

    @Test
    fun alreadyCancelledEntryPointsPerformNoStorageOrPlatformOperations() = runTest {
        val f = WakeFixture()
        val actions: List<suspend () -> Any> =
            listOf(
                { f.request() },
                { f.resume() },
                { f.read() },
                { f.complete(CollaborationRecoveryWake("old")) },
            )
        for (action in actions) {
            val job = launch {
                currentCoroutineContext().cancel()
                action()
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun lateCancelledReadCannotScheduleItsPreviouslyPendingGeneration() = runTest {
        val f = WakeFixture()
        f.request()
        f.calls.clear()
        f.afterRead = { currentCoroutineContext().cancel() }
        val job = launch {
            f.resume()
            error("Cancelled read")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("read"), f.calls)
        assertNotNull(f.retained)
    }
}

private class WakeFixture {
    val storage = Failure(FailureKind.Storage, "Unavailable storage")
    val unavailable = Failure(FailureKind.Unavailable, "Unavailable scheduler")
    val calls = mutableListOf<String>()
    val scheduled = mutableListOf<String>()
    var writes = 0
    var retained: CollaborationRecoveryWake? = null
    var writeFailure: Failure? = null
    var readFailure: Failure? = null
    var ackFailure: Failure? = null
    var scheduleResult: AppResult<Unit> = AppResult.Success(Unit)
    var afterWrite: suspend () -> Unit = {}
    var afterRead: suspend () -> Unit = {}
    var afterAck: suspend () -> Unit = {}
    var onSchedule: suspend () -> Unit = {}
    val wakes =
        object : CollaborationRecoveryWakeRepository {
            override suspend fun request(): AppResult<CollaborationRecoveryWake> {
                calls += "write"
                writeFailure?.let {
                    return AppResult.Failed(it)
                }
                val wake = CollaborationRecoveryWake("generation-${++writes}")
                retained = wake
                afterWrite()
                return AppResult.Success(wake)
            }

            override suspend fun pending(): AppResult<CollaborationRecoveryWake?> {
                calls += "read"
                readFailure?.let {
                    return AppResult.Failed(it)
                }
                val value = retained
                afterRead()
                return AppResult.Success(value)
            }

            override suspend fun acknowledge(wake: CollaborationRecoveryWake): AppResult<Boolean> {
                calls += "ack"
                ackFailure?.let {
                    return AppResult.Failed(it)
                }
                val matches = retained == wake
                if (matches) retained = null
                afterAck()
                return AppResult.Success(matches)
            }
        }
    val schedules =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                calls += "schedule"
                scheduled += key
                onSchedule()
                return scheduleResult
            }
        }
    val request = RequestCollaborationRecovery(wakes, schedules)
    val resume = ResumeCollaborationRecovery(wakes, schedules)
    val read = ReadCollaborationRecoveryWake(wakes)
    val complete = CompleteCollaborationRecoveryWake(wakes)
}
