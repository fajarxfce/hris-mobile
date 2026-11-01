package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.delivery.deliveryPartition
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationDeliveryCursorRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationDeliveryCursorStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.cursors(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationDeliveryCursorRepository(store, cipher, keys)

    private fun cursor(result: AppResult<CollaborationDeliveryCursor?>) =
        assertNotNull(assertIs<AppResult.Success<CollaborationDeliveryCursor?>>(result).value)

    @Test
    fun emptyReadCreatesNoKeyAndCommittedTurnSurvivesReauthenticationAndRestart() =
        withDatabase { f ->
            assertEquals(AppResult.Success(null), f.cursors().current(f.access))
            assertNull(f.keys.read())
            val first = cursor(f.cursors().advance(f.access, null, f.conversation))
            val raw =
                assertNotNull(
                    f.database.replicas.checkpoint(
                        historyScope(f.access),
                        deliveryPartition(f.access),
                    )
                )
            assertFalse(raw.payload.contains(f.conversation))
            assertFalse(raw.handle.partition.contains(f.conversation))
            val renewed = f.access.copy(sessionId = "renewed")
            f.reopen(renewed)
            assertEquals(AppResult.Success(first), f.cursors().current(renewed))
            assertEquals(AppResult.Success(null), f.cursors().advance(renewed, null, historyId(12)))
        }

    @Test
    fun oldGenerationCannotAdvanceAfterAnAToBToATurn() = withDatabase { f ->
        val a = cursor(f.cursors().advance(f.access, null, f.conversation))
        val b = cursor(f.cursors().advance(f.access, a, historyId(12)))
        val again = cursor(f.cursors().advance(f.access, b, f.conversation))
        assertNotEquals(a.generation, again.generation)
        assertEquals(AppResult.Success(null), f.cursors().advance(f.access, a, historyId(13)))
        assertEquals(AppResult.Success(again), f.cursors().current(f.access))
    }

    @Test
    fun competingFirstAdvanceCannotOverwriteTheWinner() = withDatabase { f ->
        var winner: CollaborationDeliveryCursor? = null
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    winner = cursor(f.cursors().advance(f.access, null, historyId(22)))
                    return f.database.replicas.replace(scope, partition, expected, snapshot)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.cursors(store).advance(f.access, null, f.conversation),
        )
        assertEquals(AppResult.Success(winner), f.cursors().current(f.access))
    }

    @Test
    fun cancellationAfterCommitDoesNotLoseTheNextTurn() = withDatabase { f ->
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    check(f.database.replicas.replace(scope, partition, expected, snapshot))
                    throw CancellationException("Fixture lost response")
                }
            }
        assertFailsWith<CancellationException> {
            f.cursors(store).advance(f.access, null, f.conversation)
        }
        f.reopen()
        assertEquals(f.conversation, cursor(f.cursors().current(f.access)).afterConversationId)
    }

    @Test
    fun tamperedGenerationScopeOrCiphertextCannotChooseTheNextRoom() = withDatabase { f ->
        val first = cursor(f.cursors().advance(f.access, null, f.conversation))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(historyScope(f.access), deliveryPartition(f.access))
            )
        for (mode in 0..4) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint =
                        when (mode) {
                            0 -> raw.copy(payload = "tampered")
                            1 -> raw.copy(revision = 1)
                            2 -> raw.copy(handle = raw.handle.copy(generation = historyId(88)))
                            3 ->
                                raw.copy(
                                    handle =
                                        raw.handle.copy(
                                            partition =
                                                deliveryPartition(
                                                    f.access.copy(companyId = historyId(89))
                                                )
                                        )
                                )
                            else -> raw.copy(published = false)
                        }
                }
            assertIs<AppResult.Failed>(f.cursors(store).current(f.access))
            assertIs<AppResult.Failed>(f.cursors(store).advance(f.access, first, historyId(90)))
        }
    }

    @Test
    fun deliveryOnlyCheckpointPreventsReplacingALostHistoryKey() = withDatabase { f ->
        cursor(f.cursors().advance(f.access, null, f.conversation))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.cursors().current(f.access))
        assertIs<AppResult.Failed>(
            f.cursors().advance(f.access.copy(companyId = historyId(92)), null, f.conversation)
        )
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, listOf(f.event()))
        )
        assertNull(f.keys.read())
    }

    @Test
    fun differentCompanyHasIndependentTurnsAndAccountChangeCannotPublish() = withDatabase { f ->
        val first = cursor(f.cursors().advance(f.access, null, f.conversation))
        assertEquals(
            AppResult.Success(null),
            f.cursors().current(f.access.copy(companyId = historyId(93))),
        )
        val other = f.access.copy(sessionId = "other", accountId = historyId(94))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.replace(scope, partition, expected, snapshot)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.cursors(store).advance(f.access, first, historyId(95)),
        )
        assertEquals(AppResult.Success(null), f.cursors().current(other))
        f.activate()
        assertEquals(AppResult.Success(first), f.cursors().current(f.access))
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
