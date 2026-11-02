package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.CollaborationPushFixture.Companion.fields
import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationPushSignalTest {
    @Test
    fun nativeHintsPersistAFreshGenerationBeforeTheFixedWorkerWithoutAcknowledging() = runTest {
        for (platform in listOf(AppPlatform.Android, AppPlatform.Ios)) {
            val f = CollaborationPushFixture()
            val receive = f.receiver(platform)
            assertEquals(AppResult.Success(true), receive(fields()))
            assertEquals(
                listOf("read", "write", "read", "read", "read", "schedule", "read"),
                f.calls,
            )
            val first = assertNotNull(f.retained)
            assertEquals(AppResult.Success(true), receive(fields(true)))
            assertNotEquals(first, f.retained)
            assertEquals(List(2) { CollaborationRecoveryWake.WORK_KEY }, f.scheduled)
        }
    }

    @Test
    fun malformedOrUnsupportedHintsPerformNoIo() = runTest {
        for (platform in listOf(AppPlatform.Desktop, AppPlatform.Web)) {
            val f = CollaborationPushFixture()
            assertEquals(AppResult.Success(false), f.receiver(platform)(fields()))
            assertTrue(f.calls.isEmpty())
        }
        val f = CollaborationPushFixture()
        assertEquals(AppResult.Success(false), f.receiver()(fields() + ("text" to "private")))
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun absentOrDifferentLocalOwnerCannotRequestRecovery() = runTest {
        val sample = CollaborationPushFixture().initial
        for (session in
            listOf(
                null,
                sample.copy(id = ""),
                sample.copy(id = "bad\n"),
                sample.copy(user = sample.user.copy(id = "different")),
                sample.copy(binding = sample.binding.copy(serverId = "different")),
                sample.copy(binding = sample.binding.copy(audience = "https://elsewhere.test/")),
            )) {
            val f = CollaborationPushFixture()
            f.session = session
            assertEquals(AppResult.Success(false), f.receiver()(fields()))
            assertEquals(listOf("read"), f.calls)
        }
    }

    @Test
    fun expiredCredentialsAndSameFamilyRenewalDoNotEraseLocalOwnership() = runTest {
        val f = CollaborationPushFixture()
        f.session =
            f.initial.copy(
                tokens = f.initial.tokens.copy(expiresAtEpochMillis = 0),
                binding = f.initial.binding.copy(expiresAtEpochMillis = 0),
            )
        f.afterWrite = {
            f.session = f.initial.copy(tokens = f.initial.tokens.copy(accessToken = "renewed"))
        }
        assertEquals(AppResult.Success(true), f.receiver()(fields()))
        assertNotNull(f.retained)
        assertEquals(1, f.scheduled.size)
    }

    @Test
    fun lateLogoutOrOwnerReplacementCannotPublishSuccessOrClearTheGlobalWake() = runTest {
        for (afterSchedule in listOf(false, true)) {
            val sample = CollaborationPushFixture().initial
            for (replacement in
                listOf(
                    null,
                    sample.copy(id = "replacement"),
                    sample.copy(user = sample.user.copy(id = "other")),
                    sample.copy(binding = sample.binding.copy(serverId = "other")),
                    sample.copy(binding = sample.binding.copy(audience = "https://elsewhere.test/")),
                )) {
                val f = CollaborationPushFixture()
                val replace: suspend () -> Unit = { f.session = replacement }
                if (afterSchedule) f.afterSchedule = replace else f.afterWrite = replace
                val failed = assertIs<AppResult.Failed>(f.receiver()(fields()))
                assertEquals("client.session_changed", failed.failure.code)
                assertEquals(FailureKind.Unauthorized, failed.failure.kind)
                assertNotNull(f.retained)
                assertEquals(if (afterSchedule) 1 else 0, f.scheduled.size)
            }
        }
    }

    @Test
    fun storageAndSchedulingFailuresRetainTheirClassificationAndCommittedFacts() = runTest {
        for (stage in 0..2) {
            val f = CollaborationPushFixture()
            val expected = if (stage == 2) f.unavailable else f.storage
            when (stage) {
                0 -> f.sessionFailure = expected
                1 -> f.writeFailure = expected
                2 -> f.scheduleFailure = expected
            }
            assertEquals(AppResult.Failed(expected), f.receiver()(fields()))
            assertEquals(stage == 2, f.retained != null)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun lateSessionReadFailureCannotMasqueradeAsSuccessfulDelivery() = runTest {
        for (read in 2..5) {
            val f = CollaborationPushFixture()
            f.onRead = { if (it == read) f.sessionFailure = f.storage }
            assertEquals(AppResult.Failed(f.storage), f.receiver()(fields()))
            assertNotNull(f.retained)
            assertEquals(if (read == 5) 1 else 0, f.scheduled.size)
        }
    }

    @Test
    fun cancelledEntryNeverReadsOrSchedules() = runTest {
        val f = CollaborationPushFixture()
        val job = launch {
            currentCoroutineContext().cancel()
            f.receiver()(fields())
            error("Cancelled receive published")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun cancellationJoinsPendingReadsWritesAndScheduling() = runTest {
        for (stage in 0..2) {
            val f = CollaborationPushFixture()
            val entered = CompletableDeferred<Unit>()
            var released = false
            val wait: suspend () -> Unit = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
            when (stage) {
                0 -> f.onRead = { wait() }
                1 -> f.beforeWrite = wait
                2 -> f.beforeSchedule = wait
            }
            val job = launch {
                f.receiver()(fields())
                error("Cancelled result")
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(released)
            assertEquals(stage == 2, f.retained != null)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun cancellationAfterCommitOrEnqueueKeepsDurableWorkAndRejectsLateResults() = runTest {
        for (scheduled in listOf(false, true)) {
            val f = CollaborationPushFixture()
            val cancel: suspend () -> Unit = { currentCoroutineContext().cancel() }
            if (scheduled) f.afterSchedule = cancel else f.afterWrite = cancel
            val job = launch {
                f.receiver()(fields())
                error("Cancelled result")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertNotNull(f.retained)
            assertEquals(if (scheduled) 1 else 0, f.scheduled.size)
        }
    }

    @Test
    fun receiverAndSchedulerTimeoutsReleaseTheirOperations() = runTest {
        for (stage in 0..2) {
            val f = CollaborationPushFixture()
            var released = false
            val wait: suspend () -> Unit = {
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
            when (stage) {
                0 -> f.onRead = { wait() }
                1 -> f.beforeWrite = wait
                2 -> f.beforeSchedule = wait
            }
            val started = testScheduler.currentTime
            val failed = assertIs<AppResult.Failed>(f.receiver()(fields()))
            assertEquals(FailureKind.Timeout, failed.failure.kind)
            assertEquals(
                if (stage == 2) "client.collaboration_recovery_schedule_timeout"
                else "client.collaboration_push_timeout",
                failed.failure.code,
            )
            assertEquals(if (stage == 2) 1_500L else 3_000L, testScheduler.currentTime - started)
            assertEquals(stage == 2, f.retained != null)
            assertTrue(released)
        }
    }

    @Test
    fun packetMutationWhileLocalReadIsPendingCannotRetargetTheCapturedHint() = runTest {
        val f = CollaborationPushFixture()
        val packet = fields(true).toMutableMap()
        f.onRead = { if (it == 1) packet.clear() }
        assertEquals(AppResult.Success(true), f.receiver()(packet))
        assertEquals(listOf(CollaborationRecoveryWake.WORK_KEY), f.scheduled)
    }
}
