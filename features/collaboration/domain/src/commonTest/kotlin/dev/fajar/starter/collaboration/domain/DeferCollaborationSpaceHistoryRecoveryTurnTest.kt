package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DeferCollaborationSpaceHistoryRecoveryTurnTest {

    @Test
    fun deferralCapturesActiveAndRequestedPredicatesBeforeAdmissionSuspends() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val rooms = mutableListOf("!active:test")
        val requested = mutableListOf("!later:test")
        val active =
            f.recovery.copy(
                roomIds = rooms,
                requestedGeneration = f.base.id(999),
                requestedRoomIds = requested,
            )
        f.turn = f.turn.copy(recovery = active)
        f.history(active.copy(roomIds = rooms.toList(), requestedRoomIds = requested.toList()))
        f.hooks["root1"] = {
            rooms.clear()
            requested.clear()
        }
        assertEquals(AppResult.Success(true), f.defer(f.turn, f.base.directory.storageFailure))
        assertEquals(listOf("!active:test"), f.retained().roomIds)
        assertEquals(listOf("!later:test"), f.retained().requestedRoomIds)
        assertNotNull(f.retained().retry)
    }

    @Test
    fun aNarrowedPendingPredicateCannotReceiveAnOlderFailure() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val active = f.recovery.copy(requestedGeneration = f.base.id(999))
        f.turn = f.turn.copy(recovery = active)
        f.history(active.copy(requestedRoomIds = listOf("!narrow:test")))
        assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
        assertEquals(0, f.writes)
    }

    @Test
    fun operationalFailureRetainsDelayWithoutAdvancingCursorOrOtherLanes() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val active =
            f.recovery.copy(
                revision = 2,
                afterPartition = "partition",
                moreHistoryPages = true,
                unresolved = true,
            )
        f.history(active)
        f.turn = f.turn.copy(recovery = active)
        val previous = assertNotNull(f.snapshot).demand
        assertEquals(AppResult.Success(true), f.defer(f.turn, f.base.directory.storageFailure))
        val expected =
            active.copy(
                revision = 3,
                retry =
                    assertNotNull(
                        nextCollaborationSpaceIncomingRetry(null, f.now.toEpochMilliseconds())
                    ),
            )
        assertEquals(expected, f.retained())
        assertEquals(previous.copy(historyRecovery = expected), assertNotNull(f.snapshot).demand)
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
        assertNull(f.reads["progress"])
    }

    @Test
    fun authorityValidationAndContentionFailuresDoNotBecomeRetry() = runTest {
        for (failure in
            listOf(
                    FailureKind.Unauthorized,
                    FailureKind.AccessDenied,
                    FailureKind.Validation,
                    FailureKind.Conflict,
                )
                .map { collaborationRecoveryFailure("failure", it).failure } +
                collaborationSpaceIncomingQueueContention().failure) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            assertEquals(AppResult.Failed(failure), f.defer(f.turn, failure))
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun laterRequestSurvivesDeferral() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.hooks["demand1"] = { f.history(f.recovery.copy(requestedGeneration = f.base.id(990))) }
        assertEquals(AppResult.Success(true), f.defer(f.turn, f.base.directory.storageFailure))
        assertEquals(f.base.id(990), f.retained().requestedGeneration)
    }

    @Test
    fun replacedTurnDoesNotReceiveOldFailure() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.history(CollaborationSpaceHistoryRecovery(f.base.id(991)))
        assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
        assertEquals(0, f.writes)
    }

    @Test
    fun replayDoesNotIncreaseFailureCountAgain() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        assertEquals(AppResult.Success(true), f.defer(f.turn, f.base.directory.storageFailure))
        val saved = f.retained()
        assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
        assertEquals(saved, f.retained())
        assertEquals(1, f.writes)
    }

    @Test
    fun retryBackoffIsBoundedAndClockRollbackRemainsEligible() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val retry =
            assertNotNull(nextCollaborationSpaceIncomingRetry(null, f.now.toEpochMilliseconds()))
        val active = f.recovery.copy(revision = 1, retry = retry)
        f.history(active)
        f.turn = f.turn.copy(recovery = active)
        f.now = Instant.fromEpochMilliseconds(retry.attemptedAtEpochMillis - 1)
        assertEquals(AppResult.Success(true), f.defer(f.turn, f.base.directory.storageFailure))
        val saved = assertNotNull(f.retained().retry)
        assertEquals(2, saved.failures)
        assertEquals(60_000L, saved.eligibleAtEpochMillis - saved.attemptedAtEpochMillis)
        assertTrue(
            eligibleCollaborationSpaceHistoryRecovery(
                f.retained(),
                saved.attemptedAtEpochMillis - 1,
            )
        )
    }

    @Test
    fun invalidClockCannotPersistAnInvalidRetry() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.now = Instant.fromEpochMilliseconds(-1)
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
                .failure
                .kind,
        )
        assertEquals(0, f.writes)
    }

    @Test
    fun sourceRootRegistrationAndAccountChangesStopDeferral() = runTest {
        for (kind in listOf("source", "root", "registration", "account", "denial")) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            f.hooks["demand1"] = {
                when (kind) {
                    "source" -> f.base.installed = null
                    "root" -> f.root = null
                    "registration" -> f.base.registration = null
                    "account" -> f.base.native.setup.session = null
                    else -> f.base.directory.deny()
                }
            }
            assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun lostReplyNeedsTheExactRetainedDelay() = runTest {
        for (commit in listOf(false, true)) {
            val f = SpaceHistoryRecoveryCompletionFixture()
            f.commit = commit
            f.lostReply = true
            val result = f.defer(f.turn, f.base.directory.storageFailure)
            if (commit) assertEquals(AppResult.Success(true), result)
            else assertEquals(AppResult.Failed(f.base.directory.storageFailure), result)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun uncommittedSuccessfulReplyCannotConfirmDelay() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.commit = false
        f.fabricatedReply = true
        assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
        assertEquals(f.recovery, f.retained())
    }

    @Test
    fun contentionPreservesConcurrentDemandAndDoesNotLoop() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.hooks["write1"] = { f.history(f.recovery.copy(requestedGeneration = f.base.id(998))) }
        assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
        assertEquals(1, f.writes)
        assertNull(f.retained().retry)
        assertEquals(f.base.id(998), f.retained().requestedGeneration)
    }

    @Test
    fun timeoutRetainsPriorTurn() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        f.hooks["demand1"] = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.defer(f.turn, f.base.directory.storageFailure))
                .failure
                .kind,
        )
        assertEquals(f.recovery, f.retained())
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationPropagatesWithoutPersistingDelay() = runTest {
        val f = SpaceHistoryRecoveryCompletionFixture()
        val reached = CompletableDeferred<Unit>()
        f.hooks["demand1"] = {
            reached.complete(Unit)
            awaitCancellation()
        }
        val task = async { f.defer(f.turn, f.base.directory.storageFailure) }
        reached.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(f.recovery, f.retained())
        assertEquals(0, f.writes)
    }
}
