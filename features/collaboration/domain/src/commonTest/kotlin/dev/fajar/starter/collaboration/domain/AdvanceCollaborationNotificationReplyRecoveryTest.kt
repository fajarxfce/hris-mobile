package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AdvanceCollaborationNotificationReplyRecoveryTest {
    @Test
    fun exactCursorAdvancesAcrossAnImmutableBatchAndFollowingPage() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        val work = expect(f.read())!!
        var cursor = work.cursor
        for (entry in work.entries) {
            val next = expect(f.advance(work, entry, cursor))
            assertNotEquals(cursor?.generation, next.generation)
            assertEquals(entry.sequence, next.afterSequence)
            cursor = next
        }
        assertEquals(listOf(5L, 6L), expect(f.read())!!.entries.map { it.sequence })
        assertEquals(4, f.advances)
        assertEquals(6, f.records.size)
    }

    @Test
    fun wrappedBatchCanAdvanceFromTheOldHigherSequenceWithoutOverwritingStaleCursor() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        f.cursor = CollaborationNotificationReplyRecoveryCursor(f.id(2000), 90)
        val work = expect(f.read())!!
        val first = expect(f.advance(work, work.entries.first()))
        assertEquals(1L, first.afterSequence)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.advance(work, work.entries[1])).failure.kind,
        )
        val second = expect(f.advance(work, work.entries[1], first))
        assertEquals(2L, second.afterSequence)
    }

    @Test
    fun malformedWorkForeignReferenceAndNonPriorExpectedCursorAreRejectedBeforeStorage() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        val work = expect(f.read())!!
        val entry = work.entries.first()
        val bad =
            listOf(
                work.copy(entries = emptyList()),
                work.copy(entries = listOf(entry, entry)),
                work.copy(afterSequence = 99),
                work.copy(cursor = CollaborationNotificationReplyRecoveryCursor("bad", 1)),
            )
        for (value in bad) assertValidation(f.advance(value, entry))
        assertValidation(f.advance(work, entry.copy(id = "n-" + "e".repeat(64))))
        assertValidation(
            f.advance(work, entry, CollaborationNotificationReplyRecoveryCursor(f.id(800), 999))
        )
        assertValidation(
            f.advance(
                work,
                entry,
                CollaborationNotificationReplyRecoveryCursor(f.id(800), entry.sequence),
            )
        )
        assertEquals(0, f.advances)
    }

    @Test
    fun parentLossBeforeOrAfterCommitNeverAcknowledgesTheOldTurn() = runTest {
        for (after in listOf(false, true)) {
            val f = CollaborationNotificationReplyRecoveryFixture()
            val work = expect(f.read())!!
            if (after) f.afterAdvance = { f.native.replaceFamily() } else f.native.replaceFamily()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.advance(work, work.entries.first())).failure.kind,
            )
            assertEquals(if (after) 1 else 0, f.advances)
            if (after) assertEquals(1L, f.cursor!!.afterSequence)
        }
    }

    @Test
    fun malformedSuccessAndStorageFailureAfterCommitDoNotBecomeAnAdvanceGrant() = runTest {
        for (mode in 0..3) {
            val f = CollaborationNotificationReplyRecoveryFixture()
            f.cursor = CollaborationNotificationReplyRecoveryCursor(f.id(2000), 0)
            val work = expect(f.read())!!
            f.advanceResult =
                when (mode) {
                    0 -> AppResult.Success(CollaborationNotificationReplyRecoveryCursor("bad", 1))
                    1 ->
                        AppResult.Success(
                            CollaborationNotificationReplyRecoveryCursor(f.id(800), 999)
                        )
                    2 -> AppResult.Success(work.cursor!!.copy(afterSequence = 1))
                    else -> AppResult.Failed(f.local.storage)
                }
            assertIs<AppResult.Failed>(f.advance(work, work.entries.first()))
            assertEquals(1L, f.cursor!!.afterSequence)
            assertEquals(6, f.records.size)
        }
    }

    @Test
    fun deadlineAndCancellationAfterCommitKeepFairSeekAndJoinBeforeRetry() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        val work = expect(f.read())!!
        var stopped = false
        f.afterAdvance = {
            try {
                delay(8_001)
            } finally {
                stopped = true
            }
        }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.advance(work, work.entries.first())).failure.kind,
        )
        assertTrue(stopped)
        assertEquals(1L, f.cursor!!.afterSequence)
        val next = expect(f.read())!!
        assertEquals(2L, next.entries.first().sequence)
        val entered = CompletableDeferred<Unit>()
        stopped = false
        f.afterAdvance = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val pending = async { f.advance(next, next.entries.first()) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(stopped)
        assertEquals(2L, f.cursor!!.afterSequence)
        f.afterAdvance = {}
        val remaining = expect(f.read())!!
        assertEquals(3L, remaining.entries.first().sequence)
        assertEquals(3L, expect(f.advance(remaining, remaining.entries.first())).afterSequence)
    }

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun assertValidation(result: AppResult<*>) {
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(result).failure.kind)
    }
}
