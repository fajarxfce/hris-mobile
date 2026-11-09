@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.RequestCollaborationRecovery
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.SyncTrigger
import dev.fajar.starter.worker.ForegroundWorkScheduler
import dev.fajar.starter.worker.RequestedSyncWorker
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationRecoveryWorkerTest {
    @Test
    fun aPersistedRequestBypassesOldOsBackoffWithoutAddingAForegroundRetryLoop() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val requests = ForegroundWorkScheduler(setOf(CollaborationRecoveryWake.WORK_KEY))
        f.onSchedule = { key ->
            assertNotNull(f.wake, "Persist the feature intent before notifying either scheduler")
            assertTrue(requests.notify(key))
        }
        val request = RequestCollaborationRecovery(f.wakes, f.schedules)
        val pendingAfterAcknowledgment = mutableListOf<CollaborationRecoveryWake?>()
        f.afterAck = { pendingAfterAcknowledgment += f.wake }
        f.onCompany = { SyncResult.Retry(Failure(FailureKind.Network, "Unavailable")) }
        val host = RequestedSyncWorker(listOf(f.worker()), requests).start(backgroundScope)
        runCurrent()
        assertTrue(f.attempted.isEmpty())
        assertIs<AppResult.Success<Unit>>(request())
        runCurrent()
        assertEquals(listOf(recoveryId(1)), f.attempted)
        val oldWake = assertNotNull(f.wake)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.attempted.size)
        f.onCompany = { SyncResult.Complete }
        assertIs<AppResult.Success<Unit>>(request())
        val newWake = assertNotNull(f.wake)
        assertNotEquals(oldWake, newWake)
        runCurrent()
        // The failed sweep retains its original generation. Completing it must not consume
        // the new request; one explicit continuation covers that newer generation.
        assertEquals(3, f.attempted.size)
        assertEquals(listOf(newWake, null), pendingAfterAcknowledgment)
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, f.attempted.size)
        host.cancelAndJoin()
        requests.close()
    }

    @Test
    fun aForegroundRequestWaitsForTheSameOsWorkerWithoutLosingItsNewWake() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val requests = ForegroundWorkScheduler(setOf(CollaborationRecoveryWake.WORK_KEY))
        f.onSchedule = { assertTrue(requests.notify(it)) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        f.onCompany = {
            active++
            maximum = maxOf(maximum, active)
            try {
                if (f.attempted.size == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                SyncResult.Complete
            } finally {
                active--
            }
        }
        val worker = f.worker()
        val os = async { worker() }
        entered.await()
        val original = assertNotNull(f.wake)
        val host = RequestedSyncWorker(listOf(worker), requests).start(backgroundScope)
        assertIs<AppResult.Success<Unit>>(RequestCollaborationRecovery(f.wakes, f.schedules)())
        assertNotEquals(original, f.wake)
        runCurrent()
        assertEquals(1, f.attempted.size)
        release.complete(Unit)
        assertEquals(SyncResult.Complete, os.await())
        runCurrent()
        assertEquals(1, maximum)
        assertEquals(2, f.attempted.size)
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
        host.cancelAndJoin()
        requests.close()
    }

    @Test
    fun inputAndOtherIndependentDeadlinesStillLeaveTheCompanyTurnAvailable() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val joined = mutableListOf<String>()
        f.onInputs = {
            try {
                awaitCancellation()
            } finally {
                joined += "input"
            }
        }
        f.onReplies = {
            assertEquals(listOf("input"), joined)
            try {
                awaitCancellation()
            } finally {
                joined += "reply"
            }
        }
        f.onPriority = {
            assertEquals(listOf("input", "reply"), joined)
            try {
                awaitCancellation()
            } finally {
                joined += "priority"
            }
        }
        f.onCompany = {
            assertEquals(listOf("input", "reply", "priority"), joined)
            delay(49_000)
            SyncResult.Complete
        }
        val result = assertIs<SyncResult.Retry>(f.worker()())
        assertEquals("client.collaboration_recovery_input_timeout", result.failure?.code)
        assertEquals(81_000L, testScheduler.currentTime)
        assertEquals(listOf(recoveryId(1)), f.attempted)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
    }

    @Test
    fun parentReplacementJoinsInputRecoveryBeforeReplyPriorityOrCompany() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.onInputs = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        f.onReplies = { error("Replaced parent must not run reply recovery") }
        f.onPriority = { error("Replaced parent must not run priority recovery") }
        val job = async { f.worker()() }
        entered.await()
        f.states.value = f.publication(null)
        assertIs<SyncResult.Blocked>(job.await())
        assertTrue(joined)
        assertTrue(f.attempted.isEmpty())
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(0, f.states.subscriptionCount.value)
    }

    @Test
    fun unfinishedInputSnapshotRetainsWakeUntilItsLaterCompletedTurn() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        f.onInputs = { SyncResult.Retry() }
        assertEquals(SyncResult.Complete, f.worker()())
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(listOf(CollaborationRecoveryWake.WORK_KEY), f.scheduled)
        f.onInputs = { SyncResult.Complete }
        assertEquals(SyncResult.Complete, f.worker()())
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun failedReplyRetainsWakeDespiteHealthyContinuationsAndStillReservesOtherLanes() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 2)
        val unavailable = Failure(FailureKind.Network, "Fictional reply failure")
        val order = mutableListOf<String>()
        f.onReplies = {
            order += "reply"
            SyncResult.Retry(unavailable)
        }
        f.onPriority = {
            order += "priority"
            SyncResult.Retry()
        }
        f.onCompany = {
            order += "company"
            SyncResult.Retry()
        }
        assertEquals(SyncResult.Retry(unavailable), f.worker()())
        assertEquals(listOf("reply", "priority", "company"), order)
        assertEquals(listOf(recoveryId(1)), f.attempted)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun priorityFailureWinsOverReplyContinuationBeforeTheCompanySweepEnds() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 2)
        val unavailable = Failure(FailureKind.Network, "Fictional priority failure")
        f.onReplies = { SyncResult.Retry() }
        f.onPriority = { SyncResult.Retry(unavailable) }
        assertEquals(SyncResult.Retry(unavailable), f.worker()())
        assertEquals(listOf(recoveryId(1)), f.attempted)
        assertNotNull(f.wake)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun replyDeadlineJoinsAndLeavesBothPriorityAndCompanyBudgetsAvailable() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        var joined = false
        f.onReplies = {
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        f.onPriority = {
            assertTrue(joined)
            delay(19_000)
            SyncResult.Complete
        }
        f.onCompany = {
            assertTrue(joined)
            delay(49_000)
            SyncResult.Complete
        }
        val start = testScheduler.currentTime
        val result = assertIs<SyncResult.Retry>(f.worker()())
        assertEquals("client.collaboration_recovery_reply_timeout", result.failure?.code)
        assertEquals(76_000L, testScheduler.currentTime - start)
        assertTrue(joined)
        assertEquals(listOf(recoveryId(1)), f.attempted)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(0, f.states.subscriptionCount.value)
    }

    @Test
    fun healthyReplyContinuationCannotAcknowledgeUntilItsLaterFinishedTurn() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        f.onReplies = { SyncResult.Retry() }
        assertEquals(SyncResult.Complete, f.worker()())
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(listOf(CollaborationRecoveryWake.WORK_KEY), f.scheduled)
        f.onReplies = { SyncResult.Complete }
        assertEquals(SyncResult.Complete, f.worker()())
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun parentReplacementCancelsAndJoinsReplyBeforeStartingOtherLanes() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val entered = CompletableDeferred<Unit>()
        var joined = false
        var priorities = 0
        f.onReplies = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        f.onPriority = {
            priorities++
            SyncResult.Complete
        }
        val job = async { f.worker()() }
        entered.await()
        f.states.value = f.publication(null)
        assertIs<SyncResult.Blocked>(job.await())
        assertTrue(joined)
        assertEquals(0, priorities)
        assertTrue(f.attempted.isEmpty())
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(0, f.states.subscriptionCount.value)
    }

    @Test
    fun blockedPriorityRetainsWakeWhileOtherOwnedCompanyWorkStillRuns() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val denied = Failure(FailureKind.AccessDenied, "Fictional company denial")
        f.onPriority = { SyncResult.Blocked(denied) }
        assertEquals(SyncResult.Retry(denied), f.worker()())
        assertEquals(listOf(recoveryId(1)), f.attempted)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
    }

    @Test
    fun failedPriorityStillVisitsEveryCompanyAndCannotAcknowledgeTheGlobalWake() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 3)
        val unavailable = Failure(FailureKind.Network, "Fictional priority failure")
        var priorities = 0
        f.onPriority = {
            priorities++
            SyncResult.Retry(unavailable)
        }
        val worker = f.worker()
        repeat(2) { assertEquals(SyncResult.Retry(unavailable), worker()) }
        assertEquals(SyncResult.Retry(unavailable), worker())
        assertEquals((1..3).map(::recoveryId), f.attempted)
        assertEquals(3, priorities)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        f.onPriority = { SyncResult.Complete }
        repeat(3) { assertEquals(SyncResult.Complete, worker()) }
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun priorityTimeoutJoinsItsWorkAndLeavesTimeForTheFairCompanyTurn() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        var closed = false
        f.onPriority = {
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        val started = testScheduler.currentTime
        val result = assertIs<SyncResult.Retry>(f.worker()())
        assertEquals("client.collaboration_recovery_priority_timeout", result.failure?.code)
        assertEquals(20_000L, testScheduler.currentTime - started)
        assertEquals(listOf(recoveryId(1)), f.attempted)
        assertTrue(closed)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
    }

    @Test
    fun healthyPriorityContinuationRetainsWakeAndSchedulesWithoutFailureBackoff() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        f.onPriority = { SyncResult.Retry() }
        assertEquals(SyncResult.Complete, f.worker()())
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(listOf(CollaborationRecoveryWake.WORK_KEY), f.scheduled)
        assertEquals(listOf(recoveryId(1)), f.attempted)
    }

    @Test
    fun multipleHealthyBatchesCoverEveryCompanyWithoutFailureBackoffOrEarlyAcknowledgment() =
        runTest {
            val f = RecoveryWorkerFixture(testScheduler)
            val worker = f.worker()
            f.onCompany = {
                if (f.attempted.size <= 10) SyncResult.Retry() else SyncResult.Complete
            }
            repeat(14) {
                assertEquals(SyncResult.Complete, worker())
                assertNotNull(f.wake)
                assertEquals(0, f.acknowledgments)
            }
            assertEquals(SyncResult.Complete, worker())
            assertEquals(List(3) { (1..5).map(::recoveryId) }.flatten(), f.attempted)
            assertNull(f.wake)
            assertEquals(1, f.wakeWrites)
            assertEquals(1, f.acknowledgments)
            assertEquals(14, f.scheduled.size)
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(0, f.states.subscriptionCount.value)
        }

    @Test
    fun aReplacementWorkerRetainsHealthyContinuationAcrossCompanyPages() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 3)
        f.onCompany = { SyncResult.Retry() }
        assertEquals(SyncResult.Complete, f.worker()())
        val wake = f.wake
        assertTrue(f.progress!!.sweep!!.continuationRequired)
        assertFalse(f.progress!!.sweep!!.retryRequired)
        f.onCompany = { SyncResult.Complete }
        repeat(2) { assertEquals(SyncResult.Complete, f.worker()()) }
        assertEquals(wake, f.wake)
        assertNull(f.progress!!.sweep!!.afterCompanyId)
        repeat(3) { assertEquals(SyncResult.Complete, f.worker()()) }
        assertEquals(List(2) { (1..3).map(::recoveryId) }.flatten(), f.attempted)
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun realFailureStillRequiresBackoffWhenLaterCompaniesHaveHealthyRemainingWork() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 3)
        f.onCompany = { grant ->
            if (grant.owner.companyId == recoveryId(1))
                SyncResult.Retry(Failure(FailureKind.Network, "Temporarily unavailable"))
            else SyncResult.Retry()
        }
        repeat(2) { assertEquals(SyncResult.Complete, f.worker()()) }
        assertTrue(f.progress!!.sweep!!.retryRequired)
        assertTrue(f.progress!!.sweep!!.continuationRequired)
        assertNotNull(assertIs<SyncResult.Retry>(f.worker()()).failure)
        assertEquals((1..3).map(::recoveryId), f.attempted)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(2, f.scheduled.size)
    }

    @Test
    fun failedContinuationSchedulingRetainsTheRestartedSweepForLaterRecovery() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val unavailable = Failure(FailureKind.Unavailable, "Unavailable scheduler")
        f.onCompany = { SyncResult.Retry() }
        f.scheduleFailure = unavailable
        assertEquals(SyncResult.Retry(unavailable), f.worker()())
        val original = assertNotNull(f.wake)
        assertEquals(CollaborationRecoverySweep(original.generation), f.progress!!.sweep)
        assertEquals(0, f.acknowledgments)
        assertEquals(3, f.scheduled.size)
        assertEquals(200L, testScheduler.currentTime)
        f.scheduleFailure = null
        f.onCompany = { SyncResult.Complete }
        assertEquals(SyncResult.Complete, f.worker()())
        assertNull(f.wake)
        assertEquals(1, f.wakeWrites)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun aFailingFirstCompanyCannotStarveLaterCompaniesOrAcknowledgeAnUncleanSweep() = runTest {
        val f = RecoveryWorkerFixture(testScheduler)
        val worker = f.worker()
        var failed = false
        f.onCompany = {
            if (!failed) {
                failed = true
                SyncResult.Retry(Failure(FailureKind.Network, "Temporarily unavailable"))
            } else SyncResult.Complete
        }
        assertEquals(SyncResult.Complete, worker())
        val original = f.wake
        repeat(3) { assertEquals(SyncResult.Complete, worker()) }
        assertIs<SyncResult.Retry>(worker())
        assertEquals((1..5).map(::recoveryId), f.attempted)
        assertEquals(original, f.wake)
        assertNull(f.progress!!.sweep!!.afterCompanyId)
        repeat(5) { assertEquals(SyncResult.Complete, worker()) }
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
        assertEquals(1, f.wakeWrites)
        assertTrue(f.scheduled.all { it == CollaborationRecoveryWake.WORK_KEY })
    }

    @Test
    fun newerWakesNeverResetTheActiveSeekAndOldCompletionSchedulesTheirOwnSweep() = runTest {
        val f = RecoveryWorkerFixture(testScheduler)
        val worker = f.worker()
        assertEquals(SyncResult.Complete, worker())
        val original = f.progress!!.sweep!!.wakeGeneration
        repeat(40) { f.wakes.request() }
        val newest = f.wake
        assertEquals(SyncResult.Complete, worker())
        assertEquals(original, f.progress!!.sweep!!.wakeGeneration)
        repeat(3) { assertEquals(SyncResult.Complete, worker()) }
        assertEquals((1..5).map(::recoveryId), f.attempted)
        assertEquals(newest, f.wake)
        assertEquals(0, f.acknowledgments)
        assertNull(f.progress!!.sweep)
        repeat(5) { worker() }
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun processInterruptionRetainsTheAttemptAndAReplacementWorkerContinuesAtLaterCompanies() =
        runTest {
            val f = RecoveryWorkerFixture(testScheduler, 3)
            val entered = CompletableDeferred<Unit>()
            f.onCompany = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = launch { f.worker()() }
            entered.await()
            job.cancelAndJoin()
            assertEquals(recoveryId(1), f.progress!!.sweep!!.inFlightCompanyId)
            val original = f.wake
            f.onCompany = { SyncResult.Complete }
            val next = f.worker()
            assertEquals(SyncResult.Complete, next())
            assertIs<SyncResult.Retry>(next())
            assertEquals((1..3).map(::recoveryId), f.attempted)
            assertEquals(original, f.wake)
            assertNull(f.progress!!.sweep!!.afterCompanyId)
            repeat(3) { assertEquals(SyncResult.Complete, next()) }
            assertNull(f.wake)
        }

    @Test
    fun twoInvocationsSerializeAndAThirdCannotCaptureAuthorityOrOpenAnotherProcessor() = runTest {
        val f = RecoveryWorkerFixture(testScheduler)
        val worker = f.worker()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        f.onCompany = { grant ->
            active++
            maximum = maxOf(maximum, active)
            try {
                if (grant.owner.companyId == recoveryId(1)) {
                    entered.complete(Unit)
                    release.await()
                }
                SyncResult.Complete
            } finally {
                active--
            }
        }
        val first = async { worker() }
        entered.await()
        val next = async { worker() }
        runCurrent()
        assertIs<SyncResult.Retry>(worker())
        assertEquals(1, f.pages)
        assertEquals(listOf(recoveryId(1)), f.attempted)
        release.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, next.await())
        assertEquals((1..2).map(::recoveryId), f.attempted)
        assertEquals(1, maximum)
        assertEquals(0, active)
        assertEquals(1, f.scheduled.size)
        assertEquals(1, f.wakeWrites)
        assertEquals(0, f.states.subscriptionCount.value)
    }

    @Test
    fun cancellingTheWaiterReleasesAdmissionAndTheCurrentProcessorSchedulesContinuation() =
        runTest {
            val f = RecoveryWorkerFixture(testScheduler)
            val worker = f.worker()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.onCompany = { grant ->
                if (grant.owner.companyId == recoveryId(1)) {
                    entered.complete(Unit)
                    release.await()
                }
                SyncResult.Complete
            }
            val first = async { worker() }
            entered.await()
            val next = launch { worker() }
            runCurrent()
            next.cancelAndJoin()
            release.complete(Unit)
            assertEquals(SyncResult.Complete, first.await())
            assertEquals(1, f.scheduled.size)
            assertEquals(SyncResult.Complete, worker())
            assertEquals((1..2).map(::recoveryId), f.attempted)
        }

    @Test
    fun aLateNoncancellableOldAccountResultCannotCompleteItsAttemptOrAcknowledgeItsWake() =
        runTest {
            val f = RecoveryWorkerFixture(testScheduler, 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.onCompany = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                SyncResult.Complete
            }
            val job = async { f.worker()() }
            entered.await()
            val old = f.progress
            val next = f.parent.copy(sessionId = "replacement", accountId = recoveryId(2000))
            f.session =
                f.session!!.copy(
                    id = next.sessionId,
                    user = f.session!!.user.copy(id = next.accountId),
                )
            f.states.value = f.publication(next)
            runCurrent()
            assertFalse(job.isCompleted)
            release.complete(Unit)
            assertIs<SyncResult.Blocked>(job.await())
            assertEquals(old, f.progress)
            assertNotNull(f.progress!!.sweep!!.inFlightCompanyId)
            assertNotNull(f.wake)
            assertEquals(0, f.acknowledgments)
            assertEquals(0, f.states.subscriptionCount.value)
        }

    @Test
    fun changingOnlyForegroundCompanyDoesNotCancelAdmittedBackgroundWork() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        f.onCompany = {
            try {
                entered.complete(Unit)
                release.await()
                SyncResult.Complete
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
        }
        val job = async { f.worker()() }
        entered.await()
        f.states.value = f.publication(foreground = f.parent.owner(recoveryId(99)))
        runCurrent()
        assertFalse(cancelled)
        release.complete(Unit)
        assertEquals(SyncResult.Complete, job.await())
        assertNull(f.wake)
        assertEquals(0, f.states.subscriptionCount.value)
    }

    @Test
    fun sourceStopLogoutAndUnprocessedFailureCancelActiveWorkAndDisposeTheObserver() = runTest {
        for (mode in 0..2) {
            val f = RecoveryWorkerFixture(testScheduler, 1)
            val entered = CompletableDeferred<Unit>()
            var closed = false
            f.onCompany = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
            val job = async { f.worker()() }
            entered.await()
            f.states.value =
                when (mode) {
                    0 -> ApplicationCollaborationState.Stopped()
                    1 -> f.publication(null)
                    else ->
                        ApplicationCollaborationState.Observed(
                            AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
                        )
                }
            assertIs<SyncResult.Blocked>(job.await())
            assertTrue(closed)
            assertNotNull(f.wake)
            assertEquals(0, f.acknowledgments)
            assertEquals(0, f.states.subscriptionCount.value)
        }
    }

    @Test
    fun inactiveDevicesAreCoveredWithoutAdmissionEnrollmentOrContentAccess() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 3)
        f.references[0] = f.references[0].copy(state = CollaborationDeviceState.REVOKED)
        f.references[1] = f.references[1].copy(state = CollaborationDeviceState.PROVISIONING)
        f.references[2] = f.references[2].copy(platform = CollaborationNativePlatform.IOS)
        val worker = f.worker()
        repeat(3) { assertEquals(SyncResult.Complete, worker()) }
        assertTrue(f.admitted.isEmpty())
        assertTrue(f.attempted.isEmpty())
        assertNull(f.wake)
    }

    @Test
    fun aConfirmedCompanyDenialIsRetainedAndCannotStarveAnotherCompany() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 2)
        val admitted = f.onAdmission
        f.onAdmission = { owner ->
            if (owner.companyId == recoveryId(1))
                AppResult.Failed(
                    Failure(
                        FailureKind.AccessDenied,
                        "Denied",
                        code = "collaboration_access_denied",
                    )
                )
            else admitted(owner)
        }
        repeat(2) { assertEquals(SyncResult.Complete, f.worker()()) }
        assertEquals(listOf(recoveryId(1)), f.denied)
        assertEquals(listOf(recoveryId(2)), f.attempted)
        assertNull(f.wake)
    }

    @Test
    fun healthyPaginationRetriesOnlyBoundedSchedulingAndPreservesItsGenerationOnFailure() =
        runTest {
            val f = RecoveryWorkerFixture(testScheduler, 3)
            f.scheduleFailure = Failure(FailureKind.Unavailable, "Unavailable scheduler")
            val worker = f.worker()
            assertIs<SyncResult.Retry>(worker())
            assertEquals(3, f.scheduled.size)
            assertEquals(200L, testScheduler.currentTime)
            val original = f.wake
            f.scheduleFailure = null
            repeat(2) { assertEquals(SyncResult.Complete, worker()) }
            assertEquals(1, f.wakeWrites)
            assertNotNull(original)
            assertNull(f.wake)
        }

    @Test
    fun timedOutCompanyReleasesItsWorkAndLaterCompanyStillRunsBeforeRetry() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 2)
        var released = false
        f.onCompany = { grant ->
            if (grant.owner.companyId == recoveryId(1)) {
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            } else SyncResult.Complete
        }
        assertEquals(SyncResult.Complete, f.worker()())
        assertIs<SyncResult.Retry>(f.worker()())
        assertTrue(released)
        assertEquals(50_000L, testScheduler.currentTime)
        assertEquals((1..2).map(::recoveryId), f.attempted)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(0, f.states.subscriptionCount.value)
    }

    @Test
    fun aLostGlobalAcknowledgmentRepeatsTheFinishedSweepWithoutLosingItsWake() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        f.ackFailure = Failure(FailureKind.Storage, "Unavailable")
        val worker = f.worker()
        assertIs<SyncResult.Retry>(worker())
        assertNull(f.progress!!.sweep)
        assertNotNull(f.wake)
        f.ackFailure = null
        assertEquals(SyncResult.Complete, worker())
        assertEquals(listOf(recoveryId(1), recoveryId(1)), f.attempted)
        assertEquals(1, f.wakeWrites)
        assertNull(f.wake)
    }

    @Test
    fun aWakeArrivingJustAfterSuccessfulAcknowledgmentIsStillScheduled() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 1)
        f.afterAck = { f.wakes.request() }
        assertEquals(SyncResult.Complete, f.worker()())
        assertNotNull(f.wake)
        assertEquals(2, f.wakeWrites)
        assertEquals(1, f.scheduled.size)
    }

    @Test
    fun missingParentsAndUnsupportedPlatformsCannotCaptureOrAcknowledgeWork() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, 0, retainedRequest = false)
        f.states.value = ApplicationCollaborationState.Pending
        for (platform in listOf(AppPlatform.Desktop, AppPlatform.Web)) {
            assertEquals(SyncResult.Complete, f.worker(platform)(SyncTrigger.Scheduled))
        }
        assertEquals(0, f.wakeWrites)
        val worker = f.worker()
        assertIs<SyncResult.Retry>(worker(SyncTrigger.Scheduled))
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(0, f.wakeWrites)
        f.states.value = f.publication(null)
        assertIs<SyncResult.Blocked>(worker(SyncTrigger.Scheduled))
        f.states.value = f.publication()
        assertEquals(SyncResult.Complete, worker(SyncTrigger.Scheduled))
        assertEquals(1, f.wakeWrites)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun aLateDirectoryAfterAccountReplacementCannotAttemptAnyCompany() = runTest {
        val f = RecoveryWorkerFixture(testScheduler)
        f.beforePage = { f.session = f.session!!.copy(id = "replacement") }
        assertIs<SyncResult.Blocked>(f.worker()())
        assertTrue(f.attempted.isEmpty())
        assertTrue(f.admitted.isEmpty())
        assertNull(f.progress!!.sweep!!.afterCompanyId)
        assertNotNull(f.wake)
    }
}
