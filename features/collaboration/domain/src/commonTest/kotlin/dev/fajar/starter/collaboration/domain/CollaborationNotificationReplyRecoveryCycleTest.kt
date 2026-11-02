package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationReplyRecoveryCycleTest {
    @Test
    fun newCycleStartsAtMinimumWhileRepeatedBeginKeepsTheSameActiveCycle() = runTest {
        val f = Fixture()
        f.base.cursor = CollaborationNotificationReplyRecoveryCursor(f.base.id(500), 100)
        val first = f.begin()
        assertTrue(first.cursor.cycle!!.initial)
        assertEquals(first, f.begin())
        assertEquals(1, f.base.advances)
        val work = f.work()!!
        assertNull(work.afterSequence)
        assertEquals(listOf(1L, 2L, 3L, 4L), work.entries.map { it.sequence })
    }

    @Test
    fun earlyTransientRemainsRequiredAfterHealthyLaterPagesAndFinish() = runTest {
        val f = Fixture()
        var state = f.begin()
        repeat(2) { page ->
            val work = f.work()!!
            for (entry in work.entries) {
                state = f.advance(work, entry, state.cursor)
                state = f.settle(state, page == 0 && entry == work.entries.first())
                assertTrue(state.cursor.cycle!!.retryRequired)
            }
        }
        assertNull(f.work())
        val finished = f.finish(state)
        assertTrue(finished.cursor.cycle!!.finished)
        assertTrue(finished.cursor.cycle!!.retryRequired)
        assertFalse(finished.cursor.cycle!!.inFlight)
        assertIs<AppResult.Failed>(f.base.read())
        val next = f.begin()
        assertNotEquals(finished.cursor.cycle!!.id, next.cursor.cycle!!.id)
        assertFalse(next.cursor.cycle!!.retryRequired)
        assertEquals(1L, f.work()!!.entries.first().sequence)
    }

    @Test
    fun interruptedLastAttemptRequiresRetryAndNewTailDoesNotHideTheOlderInput() = runTest {
        val f = Fixture()
        f.base.records = listOf(f.base.input(1))
        val start = f.begin()
        val work = f.work()!!
        val inFlight = f.advance(work, work.entries.single(), start.cursor)
        assertNull(f.work()) // An active cycle does not wrap at its empty tail.
        assertEquals(inFlight, f.begin())
        val completed = f.finish(inFlight)
        assertTrue(completed.cursor.cycle!!.retryRequired)
        f.base.records += f.base.input(50)
        f.begin()
        assertEquals(listOf(1L, 50L), f.work()!!.entries.map { it.sequence })
    }

    @Test
    fun unsettledPriorAttemptBecomesStickyWhenAdvancingToAnotherInput() = runTest {
        val f = Fixture()
        f.base.records = f.base.records.take(2)
        val start = f.begin()
        val work = f.work()!!
        val first = f.advance(work, work.entries[0], start.cursor)
        val second = f.advance(work, work.entries[1], first.cursor)
        assertTrue(second.cursor.cycle!!.retryRequired)
        val settled = f.settle(second, false)
        assertTrue(f.finish(settled).cursor.cycle!!.retryRequired)
    }

    @Test
    fun healthyOrDeniedAttemptsCanFinishWithoutAnImmediateCycleDespiteRetainedInput() = runTest {
        val f = Fixture()
        f.base.records = f.base.records.take(2)
        var state = f.begin()
        val work = f.work()!!
        for (entry in work.entries) state = f.settle(f.advance(work, entry, state.cursor), false)
        val finished = f.finish(state)
        assertFalse(finished.cursor.cycle!!.retryRequired)
        assertEquals(2, f.base.records.size) // Outcome bookkeeping never deletes or reviews input.
    }

    @Test
    fun emptyCycleAndSequenceZeroAreSupported() = runTest {
        val f = Fixture()
        val begun = f.begin()
        f.base.records = emptyList()
        val empty = f.finish(begun)
        assertTrue(empty.cursor.cycle!!.finished)
        assertFalse(empty.cursor.cycle!!.retryRequired)
        f.base.records = listOf(f.base.input(0))
        val start = f.begin()
        val work = f.work()!!
        assertEquals(0L, work.entries.single().sequence)
        val settled = f.settle(f.advance(work, work.entries.single(), start.cursor), false)
        assertFalse(f.finish(settled).cursor.cycle!!.retryRequired)
    }

    @Test
    fun finishCannotSkipAnUnvisitedTailAndStaleSettleCannotClearRetry() = runTest {
        val f = Fixture()
        val start = f.begin()
        assertKind(FailureKind.Conflict, f.finishUseCase(start))
        val work = f.work()!!
        val pending = f.advance(work, work.entries.first(), start.cursor)
        val settled = f.settle(pending, true)
        assertKind(FailureKind.Conflict, f.settleUseCase(pending, false))
        assertEquals(settled.cursor, f.base.cursor)
        assertKind(FailureKind.Conflict, f.finishUseCase(settled))
    }

    @Test
    fun oldWorkCannotAdvanceAReplacementCycleAtTheSameSequence() = runTest {
        val f = Fixture()
        val start = f.begin()
        val oldWork = f.work()!!
        val pending = f.advance(oldWork, oldWork.entries.first(), start.cursor)
        val old = f.settle(pending, false)
        val records = f.base.records
        f.base.records = emptyList()
        f.finish(old)
        f.base.records = records
        val replacement = f.begin()
        val newWork = f.work()!!
        val current =
            f.settle(f.advance(newWork, newWork.entries.first(), replacement.cursor), false)
        assertKind(
            FailureKind.Validation,
            f.base.advance(oldWork, oldWork.entries[1], current.cursor),
        )
        assertEquals(current.cursor, f.base.cursor)
    }

    @Test
    fun settleRejectsLegacyInitialFinishedAndMalformedSnapshotsWithoutWriting() = runTest {
        val f = Fixture()
        val first = f.begin()
        val writes = f.base.advances
        for (cursor in
            listOf(
                first.cursor.copy(cycle = null),
                first.cursor,
                first.cursor.copy(
                    cycle = first.cursor.cycle!!.copy(initial = false, finished = true)
                ),
                first.cursor.copy(cycle = first.cursor.cycle!!.copy(id = "bad")),
            )) assertKind(
            FailureKind.Validation,
            f.settleUseCase(first.copy(cursor = cursor), false),
        )
        assertEquals(writes, f.base.advances)
    }

    @Test
    fun malformedStoredCycleIsRejectedByBeginDiscoveryAndAdvance() = runTest {
        val f = Fixture()
        val start = f.begin()
        val work = f.work()!!
        val cycle = start.cursor.cycle!!
        for (invalid in
            listOf(
                cycle.copy(id = "bad"),
                cycle.copy(inFlight = true),
                cycle.copy(retryRequired = true),
                cycle.copy(finished = true),
                cycle.copy(initial = false, inFlight = true, finished = true),
            )) {
            f.base.cursor = start.cursor.copy(cycle = invalid)
            assertKind(FailureKind.Conflict, f.beginUseCase())
            assertKind(FailureKind.Conflict, f.base.read())
            assertKind(
                FailureKind.Validation,
                f.base.advance(work.copy(cursor = f.base.cursor), work.entries.first()),
            )
        }
    }

    @Test
    fun parentReplacementWinsAroundEachPendingCycleReadAndWrite() = runTest {
        for (stage in
            listOf(
                "begin-read",
                "begin-write",
                "settle-write",
                "finish-read",
                "finish-page",
                "finish-write",
            )) {
            val f = Fixture()
            val change: suspend () -> Unit = { f.base.native.replaceFamily() }
            val result =
                when (stage) {
                    "begin-read" -> {
                        f.base.afterCursor = change
                        f.beginUseCase()
                    }
                    "begin-write" -> {
                        f.base.afterAdvance = change
                        f.beginUseCase()
                    }
                    "settle-write" -> {
                        val start = f.begin()
                        val work = f.work()!!
                        val pending = f.advance(work, work.entries.first(), start.cursor)
                        f.base.afterAdvance = change
                        f.settleUseCase(pending, true)
                    }
                    else -> {
                        val start = f.begin()
                        f.base.records = emptyList()
                        when (stage) {
                            "finish-read" -> f.base.afterCursor = change
                            "finish-page" -> f.base.afterPage = change
                            else -> f.base.afterAdvance = change
                        }
                        f.finishUseCase(start)
                    }
                }
            assertKind(FailureKind.Unauthorized, result)
        }
    }

    @Test
    fun deadlineAfterBeginSettleOrFinishKeepsTheCommittedStateForNextRecovery() = runTest {
        for (stage in listOf("begin", "settle", "finish")) {
            val f = Fixture()
            val action: suspend () -> AppResult<CollaborationNotificationReplyRecoverySnapshot?> =
                when (stage) {
                    "begin" -> {
                        { f.beginUseCase() }
                    }
                    "settle" -> {
                        val start = f.begin()
                        val work = f.work()!!
                        val pending = f.advance(work, work.entries.first(), start.cursor)
                        val operation:
                            suspend () -> AppResult<
                                    CollaborationNotificationReplyRecoverySnapshot?
                                > =
                            {
                                f.settleUseCase(pending, true)
                            }
                        operation
                    }
                    else -> {
                        val start = f.begin()
                        f.base.records = emptyList()
                        val operation:
                            suspend () -> AppResult<
                                    CollaborationNotificationReplyRecoverySnapshot?
                                > =
                            {
                                f.finishUseCase(start)
                            }
                        operation
                    }
                }
            var joined = false
            f.base.afterAdvance = {
                try {
                    delay(8_001)
                } finally {
                    joined = true
                }
            }
            assertKind(FailureKind.Timeout, action())
            assertTrue(joined)
            val stored = f.base.cursor!!
            when (stage) {
                "begin" -> assertTrue(stored.cycle!!.initial)
                "settle" -> {
                    assertTrue(stored.cycle!!.retryRequired)
                    assertFalse(stored.cycle!!.inFlight)
                }
                else -> assertTrue(stored.cycle!!.finished)
            }
            f.base.afterAdvance = {}
            if (stage == "finish") f.base.records = listOf(f.base.input(50))
            val resumed = f.begin()
            if (stage == "finish") assertTrue(resumed.cursor.cycle!!.initial)
            else assertEquals(stored, resumed.cursor)
        }
    }

    @Test
    fun cancellationAfterSettleJoinsAndPreservesTheStickyRetry() = runTest {
        val f = Fixture()
        val start = f.begin()
        val work = f.work()!!
        val pending = f.advance(work, work.entries.first(), start.cursor)
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.base.afterAdvance = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val job = async { f.settleUseCase(pending, true) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(joined)
        assertTrue(f.base.cursor!!.cycle!!.retryRequired)
        assertFalse(f.base.cursor!!.cycle!!.inFlight)
        f.base.afterAdvance = {}
        assertEquals(f.base.cursor, f.begin().cursor)
    }

    @Test
    fun malformedCommitResultsOrLostRepliesDoNotCreateSuccessGrants() = runTest {
        for (mode in 0..3) {
            val f = Fixture()
            f.base.afterAdvance = {
                val stored = f.base.cursor!!
                f.base.advanceResult =
                    when (mode) {
                        0 ->
                            AppResult.Success(stored.copy(afterSequence = stored.afterSequence + 1))
                        1 ->
                            AppResult.Success(
                                stored.copy(cycle = stored.cycle!!.copy(id = f.base.id(1234)))
                            )
                        2 ->
                            AppResult.Success(
                                stored.copy(cycle = stored.cycle!!.copy(inFlight = true))
                            )
                        else -> AppResult.Failed(f.base.local.storage)
                    }
            }
            assertIs<AppResult.Failed>(f.beginUseCase())
            val committed = f.base.cursor!!
            f.base.afterAdvance = {}
            f.base.advanceResult = null
            assertEquals(committed, f.begin().cursor)
        }
    }

    @Test
    fun finishRejectsMalformedTailAndStorageFailureWithoutChangingCycle() = runTest {
        val f = Fixture()
        val start = f.begin()
        f.base.records = emptyList()
        f.base.pageResult = AppResult.Success(CollaborationNotificationReplyPage(emptyList(), 1))
        assertKind(FailureKind.Conflict, f.finishUseCase(start))
        f.base.pageResult = AppResult.Failed(f.base.local.storage)
        assertEquals(AppResult.Failed(f.base.local.storage), f.finishUseCase(start))
        assertEquals(start.cursor, f.base.cursor)
        f.base.pageResult = null
        assertFalse(f.finish(start).cursor.cycle!!.retryRequired)
    }

    @Test
    fun absentLegacyAndFinishedEmptyQueuesNeedNoNewCycle() = runTest {
        val f = Fixture()
        f.base.records = emptyList()
        assertEquals(AppResult.Success(null), f.beginUseCase())
        assertEquals(0, f.base.advances)
        f.base.cursor = CollaborationNotificationReplyRecoveryCursor(f.base.id(9000), 100)
        val legacy = f.base.cursor
        assertEquals(AppResult.Success(null), f.beginUseCase())
        assertEquals(legacy, f.base.cursor)
        f.base.records = listOf(f.base.input(1))
        val active = f.begin()
        f.base.records = emptyList()
        val finished = f.finish(active)
        val writes = f.base.advances
        assertEquals(AppResult.Success(null), f.beginUseCase())
        assertEquals(finished.cursor, f.base.cursor)
        assertEquals(writes, f.base.advances)
    }

    @Test
    fun beginValidatesPendingInputAndRejectsLateOwnerEvenWhenItFindsNothing() = runTest {
        val f = Fixture()
        val first = f.base.records.first()
        for (page in
            listOf(
                CollaborationNotificationReplyPage(emptyList(), 1),
                CollaborationNotificationReplyPage(f.base.records.take(2), null),
                CollaborationNotificationReplyPage(
                    listOf(
                        first.copy(review = CollaborationNotificationReplyReview.SessionChanged)
                    ),
                    null,
                ),
                CollaborationNotificationReplyPage(
                    listOf(
                        first.copy(
                            candidate =
                                first.candidate.copy(
                                    parent = first.candidate.parent.copy(accountId = f.base.id(999))
                                )
                        )
                    ),
                    null,
                ),
                CollaborationNotificationReplyPage(listOf(first), first.sequence + 1),
            )) {
            f.base.pageResult = AppResult.Success(page)
            assertKind(FailureKind.Conflict, f.beginUseCase())
        }
        assertEquals(0, f.base.advances)
        f.base.pageResult = AppResult.Success(CollaborationNotificationReplyPage(emptyList(), null))
        f.base.afterPage = { f.base.native.replaceFamily() }
        assertKind(FailureKind.Unauthorized, f.beginUseCase())
        assertEquals(0, f.base.advances)
    }

    private class Fixture {
        val base = CollaborationNotificationReplyRecoveryFixture()
        val beginUseCase =
            BeginCollaborationNotificationReplyRecoveryCycle(
                base.cursors,
                base.inputs,
                base.local.setup.sessions,
                base.local.server,
                base.local.build,
            )
        val settleUseCase =
            SettleCollaborationNotificationReplyRecoveryCycle(
                base.cursors,
                base.local.setup.sessions,
                base.local.server,
                base.local.build,
            )
        val finishUseCase =
            FinishCollaborationNotificationReplyRecoveryCycle(
                base.cursors,
                base.inputs,
                base.local.setup.sessions,
                base.local.server,
                base.local.build,
            )

        suspend fun begin() = requireNotNull(expect(beginUseCase()))

        suspend fun work() = expect(base.read())

        suspend fun settle(
            snapshot: CollaborationNotificationReplyRecoverySnapshot,
            retry: Boolean,
        ) = expect(settleUseCase(snapshot, retry))

        suspend fun finish(snapshot: CollaborationNotificationReplyRecoverySnapshot) =
            expect(finishUseCase(snapshot))

        suspend fun advance(
            work: CollaborationNotificationReplyRecoveryWork,
            entry: CollaborationNotificationReplyRecoveryReference,
            cursor: CollaborationNotificationReplyRecoveryCursor?,
        ) =
            CollaborationNotificationReplyRecoverySnapshot(
                work.parent,
                expect(base.advance(work, entry, cursor)),
            )
    }
}

private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

private fun assertKind(kind: FailureKind, result: AppResult<*>) {
    assertEquals(kind, assertIs<AppResult.Failed>(result).failure.kind)
}
