package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.CollaborationPushFixture.Companion.fields
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationPushPriorityTest {
    @Test
    fun ownedHintSurvivesExpiryOfCredentialsAndIsCoalescedBeforeScheduling() = runTest {
        val f = CollaborationPushFixture()
        f.session = f.initial.copy(binding = f.initial.binding.copy(expiresAtEpochMillis = 0))
        f.beforeSchedule = {
            assertNotNull(f.priority)
            assertNotNull(f.retained)
        }
        assertEquals(AppResult.Success(true), f.receiver()(fields(true)))
        val first = assertNotNull(f.priority)
        assertEquals(AppResult.Success(true), f.receiver()(fields(true)))
        assertEquals(first, f.priority)
        assertEquals(AppResult.Success(true), f.receiver()(fields(true) + ("revision" to "2")))
        assertEquals(2L, f.priority!!.hints.single().revision)
        assertNotEquals(first.generation, f.priority!!.generation)
    }

    @Test
    fun priorityStorageFailureAndBoundedCasContentionStillScheduleTheIndependentFullSweep() =
        runTest {
            for (failure in listOf(false, true)) {
                val f = CollaborationPushFixture()
                if (failure) f.priorityFailure = f.storage else f.loseWrites = 10
                assertEquals(AppResult.Success(true), f.receiver()(fields(true)))
                assertNull(f.priority)
                assertNotNull(f.retained)
                assertEquals(1, f.scheduled.size)
                assertEquals(if (failure) 0 else 3, f.priorityWrites)
            }
        }

    @Test
    fun oneLostCasCanRereadAndCommitWithoutAnUnboundedWait() = runTest {
        val f = CollaborationPushFixture()
        f.loseWrites = 1
        assertEquals(AppResult.Success(true), f.receiver()(fields(true)))
        assertEquals(2, f.priorityReads)
        assertEquals(2, f.priorityWrites)
        assertNotNull(f.priority)
    }

    @Test
    fun prioritySubBudgetCancelsAndJoinsBeforeSchedulingTheFullSweep() = runTest {
        for (writing in listOf(false, true)) {
            val f = CollaborationPushFixture()
            var closed = false
            val pending: suspend () -> Unit = {
                try {
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
            if (writing) f.beforePriorityWrite = pending else f.beforePriorityRead = pending
            val started = testScheduler.currentTime
            assertEquals(AppResult.Success(true), f.receiver()(fields(true)))
            assertEquals(750L, testScheduler.currentTime - started)
            assertTrue(closed)
            assertNotNull(f.retained)
            assertEquals(1, f.scheduled.size)
        }
    }

    @Test
    fun callerCancellationJoinsPendingPriorityIoAndNeverAcknowledgesOrSchedules() = runTest {
        for (writing in listOf(false, true)) {
            val f = CollaborationPushFixture()
            val entered = CompletableDeferred<Unit>()
            var closed = false
            val pending: suspend () -> Unit = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
            if (writing) f.beforePriorityWrite = pending else f.beforePriorityRead = pending
            val job = launch {
                f.receiver()(fields(true))
                error("Cancelled receive returned")
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(closed)
            assertNotNull(f.retained)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun cancellationAfterPriorityCommitKeepsTheHintAndWakeWithoutReportingSuccess() = runTest {
        val f = CollaborationPushFixture()
        f.afterPriorityWrite = { currentCoroutineContext().cancel() }
        val job = launch {
            f.receiver()(fields(true))
            error("Cancelled receive returned")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNotNull(f.priority)
        assertNotNull(f.retained)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun replacedFamilyDuringPriorityIoCannotPublishSuccessOrScheduleItsOldHint() = runTest {
        for (writing in listOf(false, true)) {
            val f = CollaborationPushFixture()
            val replace: suspend () -> Unit = {
                f.session =
                    f.initial.copy(
                        binding =
                            f.initial.binding.copy(
                                serverId = "66666666-6666-4666-8666-666666666666"
                            )
                    )
            }
            if (writing) f.afterPriorityWrite = replace else f.beforePriorityRead = replace
            assertEquals(
                "client.session_changed",
                assertIs<AppResult.Failed>(f.receiver()(fields(true))).failure.code,
            )
            assertEquals(writing, f.priority != null)
            assertTrue(f.scheduled.isEmpty())
            assertNotNull(f.retained)
        }
    }

    @Test
    fun expiredAndFuturePacketsRemainFullSweepHintsWithoutRetainedPriority() = runTest {
        for (now in listOf(0L, 1_793_491_500L)) {
            val f = CollaborationPushFixture()
            f.now = now
            assertEquals(AppResult.Success(true), f.receiver()(fields(true)))
            assertNull(f.priority)
            assertNotNull(f.retained)
            assertEquals(1, f.scheduled.size)
        }
    }
}
