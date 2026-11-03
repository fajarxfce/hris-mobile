@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationNotificationInputRecoveryTest {
    @Test
    fun finiteSnapshotUsesFourBoundedPagesAndReopensWithoutRescanningOrDecrypting() = runTest {
        val f = NotificationInputRecoveryFixture(64)
        val first = assertNotNull(value(f.begin()()))
        assertEquals(f.references, first.cursor.state.references)
        assertEquals(listOf(20, 20, 20, 4), f.pages.map { it.second })
        assertEquals(0, first.cursor.state.nextIndex)
        assertFalse(first.cursor.state.inFlight)
        assertEquals(first, value(f.begin()()))
        assertEquals(4, f.pages.size)
        assertEquals(1, f.writes)
        assertFalse(first.toString().contains(f.references.first().id))
    }

    @Test
    fun interruptedAttemptAdvancesAcrossReopenAndRetainsRetryThroughHealthyTail() = runTest {
        val f = NotificationInputRecoveryFixture(6)
        var snapshot = assertNotNull(value(f.begin()()))
        snapshot =
            value(
                f.advance()(
                    snapshot,
                    CollaborationNotificationInputRecoveryChange.Attempt(f.references.first()),
                )
            )
        assertEquals(1, snapshot.cursor.state.nextIndex)
        assertTrue(snapshot.cursor.state.inFlight)
        assertEquals(snapshot, value(f.begin()()))
        for (reference in f.references.drop(1)) {
            snapshot =
                value(
                    f.advance()(
                        snapshot,
                        CollaborationNotificationInputRecoveryChange.Attempt(reference),
                    )
                )
            snapshot =
                value(
                    f.advance()(
                        snapshot,
                        CollaborationNotificationInputRecoveryChange.Settle(false),
                    )
                )
        }
        snapshot = value(f.advance()(snapshot, CollaborationNotificationInputRecoveryChange.Finish))
        assertTrue(snapshot.cursor.state.finished)
        assertTrue(snapshot.cursor.state.retryRequired)
        val next = assertNotNull(value(f.begin()()))
        assertNotEquals(snapshot.cursor.state.cycleId, next.cursor.state.cycleId)
        assertEquals(0, next.cursor.state.nextIndex)
        assertFalse(next.cursor.state.retryRequired)
        assertEquals(f.references, next.cursor.state.references)
    }

    @Test
    fun failedFirstAttemptCannotBeClearedByHealthyLaterWorkOrLostFinishResponse() = runTest {
        val f = NotificationInputRecoveryFixture()
        var snapshot = assertNotNull(value(f.begin()()))
        f.references.forEachIndexed { index, reference ->
            snapshot =
                value(
                    f.advance()(
                        snapshot,
                        CollaborationNotificationInputRecoveryChange.Attempt(reference),
                    )
                )
            snapshot =
                value(
                    f.advance()(
                        snapshot,
                        CollaborationNotificationInputRecoveryChange.Settle(index == 0),
                    )
                )
        }
        f.lostWrite = Failure(FailureKind.Storage, "Controlled lost response")
        assertIs<AppResult.Failed>(
            f.advance()(snapshot, CollaborationNotificationInputRecoveryChange.Finish)
        )
        assertTrue(f.cursor!!.state.retryRequired)
        assertTrue(f.cursor!!.state.finished)
        f.lostWrite = null
        val next = assertNotNull(value(f.begin()()))
        assertEquals(0, next.cursor.state.nextIndex)
        assertEquals(f.references, next.cursor.state.references)
    }

    @Test
    fun arrivalsBeforeSeekAndReplacementGenerationsRequestAnotherFiniteSnapshot() = runTest {
        for (replace in listOf(false, true)) {
            val f = NotificationInputRecoveryFixture()
            f.references =
                listOf(
                    NotificationInputRecoveryFixture.reference(2),
                    NotificationInputRecoveryFixture.reference(4),
                )
            var snapshot = assertNotNull(value(f.begin()()))
            for (reference in f.references) {
                snapshot =
                    value(
                        f.advance()(
                            snapshot,
                            CollaborationNotificationInputRecoveryChange.Attempt(reference),
                        )
                    )
                snapshot =
                    value(
                        f.advance()(
                            snapshot,
                            CollaborationNotificationInputRecoveryChange.Settle(false),
                        )
                    )
            }
            f.references =
                if (replace)
                    f.references.map {
                        it.copy(generation = CollaborationReadStateFixture.id(1999))
                    }
                else listOf(NotificationInputRecoveryFixture.reference(1)) + f.references
            val finished =
                value(f.advance()(snapshot, CollaborationNotificationInputRecoveryChange.Finish))
            assertTrue(finished.cursor.state.continuationRequired)
            assertFalse(finished.cursor.state.retryRequired)
            assertEquals(f.references, assertNotNull(value(f.begin()())).cursor.state.references)
        }
    }

    @Test
    fun malformedMetadataAndInvalidTransitionNeverAdvanceProgress() = runTest {
        for (bad in
            listOf(
                listOf(
                    NotificationInputRecoveryFixture.reference(1),
                    NotificationInputRecoveryFixture.reference(1),
                ),
                listOf(
                    NotificationInputRecoveryFixture.reference(2),
                    NotificationInputRecoveryFixture.reference(1),
                ),
                listOf(NotificationInputRecoveryFixture.reference(1).copy(generation = "invalid")),
                (1..21).map { NotificationInputRecoveryFixture.reference(it) },
            )) {
            val f = NotificationInputRecoveryFixture()
            f.forcedPage = AppResult.Success(bad)
            assertIs<AppResult.Failed>(f.begin()())
            assertEquals(0, f.writes)
        }
        val f = NotificationInputRecoveryFixture()
        val snapshot = assertNotNull(value(f.begin()()))
        val before = f.writes
        for (change in
            listOf(
                CollaborationNotificationInputRecoveryChange.Attempt(f.references.last()),
                CollaborationNotificationInputRecoveryChange.Settle(false),
                CollaborationNotificationInputRecoveryChange.Finish,
            )) assertIs<AppResult.Failed>(f.advance()(snapshot, change))
        assertEquals(before, f.writes)
    }

    @Test
    fun staleAndUncertainWritesCannotReplaceOrClaimAReplacementSnapshot() = runTest {
        val f = NotificationInputRecoveryFixture()
        val original = assertNotNull(value(f.begin()()))
        val attempted =
            value(
                f.advance()(
                    original,
                    CollaborationNotificationInputRecoveryChange.Attempt(f.references.first()),
                )
            )
        assertIs<AppResult.Failed>(
            f.advance()(
                original,
                CollaborationNotificationInputRecoveryChange.Attempt(f.references.first()),
            )
        )
        assertEquals(attempted.cursor, f.cursor)
        f.forcedWrite = AppResult.Success(attempted.cursor)
        assertIs<AppResult.Failed>(
            f.advance()(attempted, CollaborationNotificationInputRecoveryChange.Settle(false))
        )
        assertEquals(attempted.cursor, f.cursor)
    }

    @Test
    fun parentReplacementAfterEveryBeginBoundaryWithholdsSuccess() = runTest {
        for (boundary in 0..2) {
            val f = NotificationInputRecoveryFixture()
            when (boundary) {
                0 -> f.afterRead = { f.replaceParent() }
                1 -> f.afterPage = { f.replaceParent() }
                2 -> f.afterWrite = { f.replaceParent() }
            }
            assertIs<AppResult.Failed>(f.begin()())
            assertEquals(if (boundary == 2) 1 else 0, f.writes)
        }
    }

    @Test
    fun deadlineAndCancellationJoinMetadataReadWithoutCreatingProgress() = runTest {
        val f = NotificationInputRecoveryFixture()
        var joined = 0
        f.afterPage = {
            try {
                awaitCancellation()
            } finally {
                joined++
            }
        }
        val pending = launch { f.begin()() }
        runCurrent()
        pending.cancelAndJoin()
        assertEquals(1, joined)
        assertEquals(0, f.writes)
        val timed = f.begin()()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(timed).failure.kind)
        assertEquals(2, joined)
        assertEquals(0, f.writes)
        f.afterPage = {}
        assertNotNull(value(f.begin()()))
    }

    @Test
    fun emptyQueueAndWrongExpectedParentDoNotCreateAKeyOrCursor() = runTest {
        val f = NotificationInputRecoveryFixture(0)
        assertNull(value(f.begin()()))
        assertEquals(0, f.writes)
        val reads = f.reads
        assertIs<AppResult.Failed>(f.begin()(f.native.parent.copy(sessionId = "wrong-parent")))
        assertEquals(reads, f.reads)
        assertEquals(0, f.writes)
    }

    private fun <T> value(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value
}
