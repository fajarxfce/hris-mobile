package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.notifications.NOTIFICATION_REPLY_RECOVERY_PARTITION
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationNotificationReplyRecoveryCursorRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

/** Real SQLite/IndexedDB and content cipher; local credential material stays in the fixture. */
abstract class CollaborationNotificationReplyRecoveryStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun exactSeekReopensAndStaleAdvanceCannotOverwriteIt() = withDatabase { f ->
        val history = f.raw()
        assertNull(expect(f.cursors().current(f.access())))
        val first = expect(f.cursors().advance(f.access(), null, 10))!!
        val raw = f.rawCursor()!!
        assertTrue(raw.payload.length <= 2048)
        assertFalse(raw.payload.contains(first.generation))
        f.reopen()
        assertEquals(first, expect(f.cursors().current(f.access())))
        val next = expect(f.cursors().advance(f.access(), first, 20))!!
        assertNotEquals(first.generation, next.generation)
        assertNull(expect(f.cursors().advance(f.access(), first, 30)))
        assertNull(expect(f.cursors().advance(f.access(), null, 30)))
        assertEquals(next, expect(f.cursors().current(f.access())))
        assertEquals(history, f.raw())
    }

    @Test
    fun wrappedSeekUsesTheExactPriorGenerationAndSurvivesAnotherReopen() = withDatabase { f ->
        val last = expect(f.cursors().advance(f.access(), null, 100))!!
        val wrapped = expect(f.cursors().advance(f.access(), last, 0))!!
        assertNotEquals(last.generation, wrapped.generation)
        f.reopen()
        assertEquals(wrapped, expect(f.cursors().current(f.access())))
        assertNull(expect(f.cursors().advance(f.access(), last, 101)))
        assertEquals(wrapped, expect(f.cursors().current(f.access())))
    }

    @Test
    fun sameAccountNewSessionKeepsSeekAndAnotherAccountCannotReadOrOverwriteIt() =
        withDatabase { f ->
            val first = expect(f.cursors().advance(f.access(), null, 10))!!
            val renewed = f.access.copy(sessionId = "renewed-local-login")
            f.reopen(renewed)
            val access =
                CollaborationRecoveryAccess(renewed.sessionId, renewed.audience, renewed.accountId)
            assertEquals(first, expect(f.cursors().current(access)))
            val other = renewed.copy(accountId = historyId(9999))
            f.activate(other)
            val otherAccess =
                CollaborationRecoveryAccess(other.sessionId, other.audience, other.accountId)
            assertNull(expect(f.cursors().current(otherAccess)))
            assertNull(expect(f.cursors().advance(otherAccess, first, 20)))
            assertNull(expect(f.cursors().advance(access, first, 20)))
            assertNull(expect(f.cursors().advance(access, null, 20)))
            f.activate()
            assertEquals(first, expect(f.cursors().current(f.access())))
        }

    @Test
    fun absentOrLostHistoryKeyNeverCreatesNewMaterialOrChangesStoredFacts() =
        withDatabase(seed = false) { f ->
            assertNull(f.keys.read())
            assertIs<AppResult.Failed>(f.cursors().advance(f.access(), null, 1))
            assertNull(f.keys.read())
            assertNull(f.rawCursor())
            f.merge(f.event())
            val history = f.raw()
            val first = expect(f.cursors().advance(f.access(), null, 1))!!
            val raw = f.rawCursor()
            val key = f.keys.read()
            f.keys.write(null)
            assertIs<AppResult.Failed>(f.cursors().current(f.access()))
            assertIs<AppResult.Failed>(f.cursors().advance(f.access(), first, 2))
            assertNull(f.keys.read())
            assertEquals(raw, f.rawCursor())
            assertEquals(history, f.raw())
            f.keys.write(key)
            assertEquals(first, expect(f.cursors().current(f.access())))
        }

    @Test
    fun tamperedMetadataCiphertextAndCrossAccountReplayAreRejectedWithoutChangingTheCursor() =
        withDatabase { f ->
            val first = expect(f.cursors().advance(f.access(), null, 10))!!
            val raw = f.rawCursor()!!
            val other = f.access().copy(accountId = historyId(9999))
            val mutations =
                listOf(
                    raw.copy(revision = 1),
                    raw.copy(published = false),
                    raw.copy(handle = raw.handle.copy(generation = historyId(9998))),
                    raw.copy(payload = "not-base64"),
                    raw.copy(payload = "x".repeat(2049)),
                    raw.copy(handle = raw.handle.copy(partition = "wrong-partition")),
                )
            for (changed in mutations) {
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun checkpoint(
                            scope: ReplicaScope,
                            partition: String,
                        ): ReplicaCheckpoint = changed
                    }
                assertIs<AppResult.Failed>(f.cursors(store).current(f.access()))
            }
            val copied = raw.copy(handle = raw.handle.copy(scope = recoveryScope(other)))
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint = copied
                }
            assertIs<AppResult.Failed>(f.cursors(store).current(other))
            assertEquals(raw, f.rawCursor())
            assertEquals(first, expect(f.cursors().current(f.access())))
        }

    @Test
    fun cancellationAfterAtomicAdvanceKeepsTheSeekForTheNextProcessContainer() = withDatabase { f ->
        val actual = f.database.replicas
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val store =
            object : ReplicaStore by actual {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    assertTrue(actual.replace(scope, partition, expected, snapshot))
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        coroutineScope {
            val pending = async { f.cursors(store).advance(f.access(), null, 12) }
            entered.await()
            pending.cancelAndJoin()
        }
        assertTrue(stopped)
        f.reopen()
        val stored = expect(f.cursors().current(f.access()))!!
        assertEquals(12L, stored.afterSequence)
        assertNull(expect(f.cursors().advance(f.access(), null, 1)))
        assertEquals(13L, expect(f.cursors().advance(f.access(), stored, 13))!!.afterSequence)
    }

    @Test
    fun invalidSequencesAndMalformedExpectedCursorNeverWrite() = withDatabase { f ->
        for (sequence in listOf(-1L, 9_007_199_254_740_991L, Long.MAX_VALUE)) assertIs<
            AppResult.Failed
        >(
            f.cursors().advance(f.access(), null, sequence)
        )
        assertIs<AppResult.Failed>(
            f.cursors()
                .advance(f.access(), CollaborationNotificationReplyRecoveryCursor("bad", 1), 2)
        )
        assertNull(f.rawCursor())
        val last = expect(f.cursors().advance(f.access(), null, 9_007_199_254_740_990L))!!
        f.reopen()
        assertEquals(last, expect(f.cursors().current(f.access())))
    }

    @Test
    fun legacySeekUpgradesToCycleWithoutChangingTheKeyOrHistory() = withDatabase { f ->
        val legacy = expect(f.cursors().advance(f.access(), null, 10))!!
        val key = f.keys.read()
        val history = f.raw()
        assertNull(legacy.cycle)
        f.reopen()
        assertEquals(legacy, expect(f.cursors().current(f.access())))
        val initial =
            CollaborationNotificationReplyRecoveryCycle(historyId(9500), true, false, false, false)
        val upgraded =
            expect(f.cursors().replace(f.access(), legacy, legacy.afterSequence, initial))!!
        f.reopen()
        assertEquals(upgraded, expect(f.cursors().current(f.access())))
        assertEquals(initial, upgraded.cycle)
        assertEquals(key, f.keys.read())
        assertEquals(history, f.raw())
    }

    @Test
    fun stickyRetryAndFinishedCycleReopenWhileStaleSettlementAndLegacyAdvanceCannotOverwrite() =
        withDatabase { f ->
            val initial =
                CollaborationNotificationReplyRecoveryCycle(
                    historyId(9501),
                    true,
                    false,
                    false,
                    false,
                )
            val begun = expect(f.cursors().replace(f.access(), null, 0, initial))!!
            val attempt = initial.copy(initial = false, inFlight = true)
            val pending = expect(f.cursors().replace(f.access(), begun, 1, attempt))!!
            f.reopen()
            assertEquals(pending, expect(f.cursors().current(f.access())))
            val retry = attempt.copy(inFlight = false, retryRequired = true)
            val settled = expect(f.cursors().replace(f.access(), pending, 1, retry))!!
            assertNull(
                expect(
                    f.cursors().replace(f.access(), pending, 1, retry.copy(retryRequired = false))
                )
            )
            assertIs<AppResult.Failed>(f.cursors().advance(f.access(), settled, 2))
            val finished =
                expect(f.cursors().replace(f.access(), settled, 1, retry.copy(finished = true)))!!
            f.reopen()
            assertEquals(finished, expect(f.cursors().current(f.access())))
            assertTrue(finished.cycle!!.retryRequired)
        }

    @Test
    fun invalidCycleCombinationsNeverReplaceTheStoredCursor() = withDatabase { f ->
        val cycle =
            CollaborationNotificationReplyRecoveryCycle(historyId(9502), true, false, false, false)
        val current = expect(f.cursors().replace(f.access(), null, 0, cycle))!!
        val raw = f.rawCursor()
        for (invalid in
            listOf(
                cycle.copy(id = "bad"),
                cycle.copy(inFlight = true),
                cycle.copy(retryRequired = true),
                cycle.copy(finished = true),
                cycle.copy(initial = false, inFlight = true, finished = true),
            )) assertIs<AppResult.Failed>(f.cursors().replace(f.access(), current, 1, invalid))
        assertEquals(raw, f.rawCursor())
        assertEquals(current, expect(f.cursors().current(f.access())))
    }

    @Test
    fun cancellationAfterCycleCommitKeepsInFlightForTheNextContainer() = withDatabase { f ->
        val actual = f.database.replicas
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val store =
            object : ReplicaStore by actual {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    assertTrue(actual.replace(scope, partition, expected, snapshot))
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        val cycle =
            CollaborationNotificationReplyRecoveryCycle(historyId(9503), false, true, false, false)
        coroutineScope {
            val pending = async { f.cursors(store).replace(f.access(), null, 1, cycle) }
            entered.await()
            pending.cancelAndJoin()
        }
        assertTrue(stopped)
        f.reopen()
        assertEquals(cycle, expect(f.cursors().current(f.access()))!!.cycle)
        assertNull(expect(f.cursors().replace(f.access(), null, 1, cycle.copy(inFlight = false))))
    }

    @Test
    fun maximumLegacySequenceUpgradesAndReopensWithoutRounding() = withDatabase { f ->
        val sequence = 9_007_199_254_740_990L
        val legacy = expect(f.cursors().advance(f.access(), null, sequence))!!
        val cycle =
            CollaborationNotificationReplyRecoveryCycle(historyId(9504), true, false, false, false)
        val upgraded = expect(f.cursors().replace(f.access(), legacy, sequence, cycle))!!
        f.reopen()
        assertEquals(sequence, expect(f.cursors().current(f.access()))!!.afterSequence)
        assertEquals(upgraded, expect(f.cursors().current(f.access())))
    }

    private fun CollaborationHistoryFixture.access() =
        CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)

    private fun CollaborationHistoryFixture.cursors(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationNotificationReplyRecoveryCursorRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.rawCursor() =
        database.replicas.checkpoint(recoveryScope(access()), NOTIFICATION_REPLY_RECOVERY_PARTITION)

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun withDatabase(
        seed: Boolean = true,
        block: suspend (CollaborationHistoryFixture) -> Unit,
    ) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            if (seed) f.merge(f.event())
            block(f)
        } finally {
            f.close()
        }
    }
}
