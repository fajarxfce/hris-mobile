package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationPushPriorityRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationPushPriorityStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.parent() =
        CollaborationNativeParent(
            access.sessionId,
            access.audience,
            access.accountId,
            historyId(9500),
        )

    private fun CollaborationHistoryFixture.priorities(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationPushPriorityRepository(store, cipher, keys)

    private fun hint(index: Int = 1) =
        CollaborationPushHint(
            historyId(index),
            historyId(100 + index),
            historyId(200 + index),
            1,
            1_793_491_200L,
            1_793_491_500L,
            "!room-$index:example.test",
            "\$event-$index",
        )

    private fun value(result: AppResult<CollaborationPushPriority?>) =
        assertNotNull(assertIs<AppResult.Success<CollaborationPushPriority?>>(result).value)

    @Test
    fun boundedWorstCasePayloadSurvivesDatabaseReopenAndSameFamilyRenewal() = withDatabase { f ->
        val parent = f.parent()
        val hints =
            (1..16).map {
                hint(it)
                    .copy(
                        revision = Long.MAX_VALUE,
                        roomId = "!" + "\"".repeat(254),
                        matrixEventId = "$" + "\\".repeat(254),
                    )
            }
        val saved = value(f.priorities().replace(parent, null, hints))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    pushPriorityScope(parent),
                    pushPriorityPartition(parent),
                )
            )
        assertTrue(raw.payload.length <= 32768)
        assertFalse(raw.payload.contains(hints.first().companyId))
        assertFalse(raw.payload.contains("example.test"))
        val originalKey = f.keys.read()
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        assertEquals(
            AppResult.Success(saved),
            f.priorities().current(parent.copy(sessionId = renewed.sessionId)),
        )
        assertEquals(originalKey, f.keys.read())
    }

    @Test
    fun nativeFamiliesHaveIndependentQueuesAndCannotTransplantCiphertext() = withDatabase { f ->
        val parent = f.parent()
        val other = parent.copy(nativeSessionId = historyId(9501))
        val saved = value(f.priorities().replace(parent, null, listOf(hint())))
        assertEquals(AppResult.Success(null), f.priorities().current(other))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    pushPriorityScope(parent),
                    pushPriorityPartition(parent),
                )
            )
        val transplanted =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                    raw.copy(handle = raw.handle.copy(scope = scope, partition = partition))
            }
        assertIs<AppResult.Failed>(f.priorities(transplanted).current(other))
        val second = value(f.priorities().replace(other, null, listOf(hint(2))))
        f.reopen()
        assertEquals(AppResult.Success(saved), f.priorities().current(parent))
        assertEquals(AppResult.Success(second), f.priorities().current(other))
    }

    @Test
    fun staleAckAndEmptyToActiveTransitionsCannotEraseANewerGeneration() = withDatabase { f ->
        val parent = f.parent()
        val first = value(f.priorities().replace(parent, null, listOf(hint())))
        val newer = value(f.priorities().replace(parent, first, listOf(hint().copy(revision = 2))))
        assertEquals(AppResult.Success(null), f.priorities().replace(parent, first, emptyList()))
        val empty = value(f.priorities().replace(parent, newer, emptyList()))
        val again = value(f.priorities().replace(parent, empty, listOf(hint())))
        assertNotEquals(first.generation, again.generation)
        assertEquals(AppResult.Success(null), f.priorities().replace(parent, first, emptyList()))
        f.reopen()
        assertEquals(AppResult.Success(again), f.priorities().current(parent))
    }

    @Test
    fun competingRepositoriesCommitExactlyOneInitialGenerationAndProtectedKey() =
        withDatabase { f ->
            val results = coroutineScope {
                (1..8)
                    .map { index ->
                        async { f.priorities().replace(f.parent(), null, listOf(hint(index))) }
                    }
                    .awaitAll()
            }
            val winners =
                results
                    .map { assertIs<AppResult.Success<CollaborationPushPriority?>>(it).value }
                    .filterNotNull()
            assertEquals(1, winners.size)
            f.reopen()
            assertEquals(AppResult.Success(winners.single()), f.priorities().current(f.parent()))
        }

    @Test
    fun processLossAfterCommitRetainsEncryptedHintsAndDoesNotReportCompletion() =
        withDatabase { f ->
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: ReplicaWakeRequest?,
                    ): Boolean {
                        check(
                            f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                        )
                        throw CancellationException("Fictional response lost after commit")
                    }
                }
            assertFailsWith<CancellationException> {
                f.priorities(store).replace(f.parent(), null, listOf(hint()))
            }
            f.reopen()
            assertEquals(listOf(hint()), value(f.priorities().current(f.parent())).hints)
        }

    @Test
    fun callerMutationDuringIoCannotChangeTheCapturedQueue() = withDatabase { f ->
        val hints = mutableListOf(hint())
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    hints.clear()
                    return f.database.replicas.checkpoint(scope, partition)
                }
            }
        val saved = value(f.priorities(store).replace(f.parent(), null, hints))
        assertTrue(hints.isEmpty())
        assertEquals(listOf(hint()), saved.hints)
        f.reopen()
        assertEquals(AppResult.Success(saved), f.priorities().current(f.parent()))
    }

    @Test
    fun accountReplacementDuringCommitCannotPublishIntoTheReplacementAccount() = withDatabase { f ->
        val parent = f.parent()
        val first = value(f.priorities().replace(parent, null, listOf(hint())))
        val other = f.access.copy(sessionId = "other", accountId = historyId(9502))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.priorities(store).replace(parent, first, emptyList()),
        )
        assertEquals(
            AppResult.Success(null),
            f.priorities()
                .current(parent.copy(sessionId = other.sessionId, accountId = other.accountId)),
        )
        f.activate()
        assertEquals(AppResult.Success(first), f.priorities().current(parent))
    }

    @Test
    fun changedAuthenticatedMetadataAndOversizedRecordsCannotBeReadOrAcknowledged() =
        withDatabase { f ->
            val parent = f.parent()
            val first = value(f.priorities().replace(parent, null, listOf(hint())))
            val raw =
                assertNotNull(
                    f.database.replicas.checkpoint(
                        pushPriorityScope(parent),
                        pushPriorityPartition(parent),
                    )
                )
            for (mode in 0..6) {
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                            when (mode) {
                                0 -> raw.copy(payload = "invalid")
                                1 ->
                                    raw.copy(handle = raw.handle.copy(generation = historyId(9503)))
                                2 ->
                                    raw.copy(
                                        handle =
                                            raw.handle.copy(
                                                scope = scope.copy(accountKey = "other")
                                            )
                                    )
                                3 ->
                                    raw.copy(
                                        handle = raw.handle.copy(partition = partition + "other")
                                    )
                                4 -> raw.copy(published = false)
                                5 -> raw.copy(revision = 1)
                                else -> raw.copy(payload = "a".repeat(32769))
                            }
                    }
                assertIs<AppResult.Failed>(f.priorities(store).current(parent))
                assertIs<AppResult.Failed>(f.priorities(store).replace(parent, first, emptyList()))
            }
            assertEquals(AppResult.Success(first), f.priorities().current(parent))
        }

    @Test
    fun lostKeyCannotBeRegeneratedOverPriorityOnlyDataFromAnInactiveAccount() = withDatabase { f ->
        value(f.priorities().replace(f.parent(), null, listOf(hint())))
        f.keys.write(null)
        val other = f.access.copy(sessionId = "other", accountId = historyId(9504))
        f.activate(other)
        val parent = f.parent().copy(sessionId = other.sessionId, accountId = other.accountId)
        assertIs<AppResult.Failed>(f.priorities().replace(parent, null, listOf(hint(2))))
        assertNull(f.keys.read())
    }

    @Test
    fun invalidQueuesDoNotCreateKeysAndPendingReadsAreCancelledAndJoined() = withDatabase { f ->
        for (hints in
            listOf(
                List(17) { hint(it) },
                listOf(hint(), hint()),
                listOf(hint().copy(revision = 0)),
            )) {
            assertIs<AppResult.Failed>(f.priorities().replace(f.parent(), null, hints))
        }
        assertNull(f.keys.read())
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            var closed = false
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            closed = true
                        }
                    }
                }
            val job = launch {
                f.priorities(store).current(f.parent())
                error("Cancelled read returned")
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(closed)
        }
    }

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
