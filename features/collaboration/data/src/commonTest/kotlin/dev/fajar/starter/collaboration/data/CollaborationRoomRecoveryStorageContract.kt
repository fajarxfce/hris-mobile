package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.data.recovery.roomRecoveryPartition
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationRoomRecoveryRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationRoomRecoveryStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.progress(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationRoomRecoveryRepository(store, cipher, keys)

    private fun position() =
        CollaborationRoomRecoveryPosition(historyId(500), historyId(500), true, true)

    private fun value(result: AppResult<CollaborationRoomRecoveryProgress?>) =
        assertNotNull(assertIs<AppResult.Success<CollaborationRoomRecoveryProgress?>>(result).value)

    @Test
    fun encryptedAttemptAndIndependentFlagsSurviveReopenAndReauthentication() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.progress().current(f.access))
        assertNull(f.keys.read())
        val saved = value(f.progress().replace(f.access, null, position()))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    historyScope(f.access),
                    roomRecoveryPartition(f.access),
                )
            )
        assertFalse(raw.payload.contains(position().afterConversationId!!))
        assertFalse(raw.handle.partition.contains(position().afterConversationId!!))
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        assertEquals(AppResult.Success(saved), f.progress().current(renewed))
        assertEquals(AppResult.Success(null), f.progress().replace(renewed, null, position()))
    }

    @Test
    fun companiesAndAccountsKeepSeparateRecoveryPositions() = withDatabase { f ->
        val first = value(f.progress().replace(f.access, null, position()))
        val otherCompany = f.access.copy(companyId = historyId(600))
        assertEquals(AppResult.Success(null), f.progress().current(otherCompany))
        val second =
            value(f.progress().replace(otherCompany, null, position().copy(retryRequired = false)))
        f.reopen()
        assertEquals(AppResult.Success(first), f.progress().current(f.access))
        assertEquals(AppResult.Success(second), f.progress().current(otherCompany))
        val otherAccount = f.access.copy(sessionId = "other", accountId = historyId(601))
        f.activate(otherAccount)
        assertEquals(AppResult.Success(null), f.progress().current(otherAccount))
        f.activate()
        assertEquals(AppResult.Success(first), f.progress().current(f.access))
    }

    @Test
    fun resettingThenRepeatingAPositionCannotReuseAnOldGeneration() = withDatabase { f ->
        val first = value(f.progress().replace(f.access, null, position()))
        val reset =
            value(f.progress().replace(f.access, first, CollaborationRoomRecoveryPosition()))
        val again = value(f.progress().replace(f.access, reset, position()))
        assertNotEquals(first.generation, again.generation)
        assertEquals(
            AppResult.Success(null),
            f.progress().replace(f.access, first, CollaborationRoomRecoveryPosition()),
        )
        assertEquals(AppResult.Success(again), f.progress().current(f.access))
    }

    @Test
    fun competingOwnersCannotOverwriteACommittedAttempt() = withDatabase { f ->
        val results = coroutineScope {
            (1..16)
                .map { index ->
                    async {
                        f.progress()
                            .replace(
                                f.access,
                                null,
                                position()
                                    .copy(
                                        afterConversationId = historyId(600 + index),
                                        inFlightConversationId = historyId(600 + index),
                                    ),
                            )
                    }
                }
                .awaitAll()
        }
        val saved =
            results
                .map { assertIs<AppResult.Success<CollaborationRoomRecoveryProgress?>>(it).value }
                .filterNotNull()
        assertEquals(1, saved.size)
        assertEquals(AppResult.Success(saved.single()), f.progress().current(f.access))
    }

    @Test
    fun cancelledCommitKeepsTheAttemptForTheNextProcess() = withDatabase { f ->
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    check(f.database.replicas.replace(scope, partition, expected, snapshot, wake))
                    throw CancellationException("Fixture lost response")
                }
            }
        assertFailsWith<CancellationException> {
            f.progress(store).replace(f.access, null, position())
        }
        f.reopen()
        assertEquals(position(), value(f.progress().current(f.access)).position)
    }

    @Test
    fun tamperingCannotTransferProgressToAnotherCompanyOrClearItsFlags() = withDatabase { f ->
        val first = value(f.progress().replace(f.access, null, position()))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    historyScope(f.access),
                    roomRecoveryPartition(f.access),
                )
            )
        for (mode in 0..6) {
            val access = if (mode == 2) f.access.copy(companyId = historyId(702)) else f.access
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint =
                        when (mode) {
                            0 -> raw.copy(payload = "invalid")
                            1 -> raw.copy(handle = raw.handle.copy(generation = historyId(703)))
                            2 -> raw.copy(handle = raw.handle.copy(partition = partition))
                            3 ->
                                raw.copy(
                                    handle =
                                        raw.handle.copy(scope = scope.copy(accountKey = "foreign"))
                                )
                            4 -> raw.copy(published = false)
                            5 -> raw.copy(revision = 1)
                            else -> raw.copy(payload = "a".repeat(2049))
                        }
                }
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(f.progress(store).current(access)).failure.kind,
            )
            assertIs<AppResult.Failed>(
                f.progress(store).replace(access, first, CollaborationRoomRecoveryPosition())
            )
        }
        assertEquals(AppResult.Success(first), f.progress().current(f.access))
    }

    @Test
    fun recoveryRowsPreventReplacingALostProtectedHistoryKey() = withDatabase { f ->
        value(f.progress().replace(f.access, null, position()))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.progress().current(f.access))
        val other = f.access.copy(sessionId = "other", accountId = historyId(704))
        f.activate(other)
        assertIs<AppResult.Failed>(f.progress().replace(other, null, position()))
        assertNull(f.keys.read())
    }

    @Test
    fun accountReplacementAtAtomicCommitCannotPublishIntoTheNewAccount() = withDatabase { f ->
        val first = value(f.progress().replace(f.access, null, position()))
        val other = f.access.copy(sessionId = "other", accountId = historyId(705))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.progress(store).replace(f.access, first, CollaborationRoomRecoveryPosition()),
        )
        assertEquals(AppResult.Success(null), f.progress().current(other))
        f.activate()
        assertEquals(AppResult.Success(first), f.progress().current(f.access))
    }

    @Test
    fun malformedPositionsCannotCreateKeysOrRecords() = withDatabase { f ->
        for (invalid in
            listOf(
                position().copy(afterConversationId = "invalid"),
                position().copy(afterConversationId = null),
                position().copy(inFlightConversationId = historyId(706)),
            )) {
            assertIs<AppResult.Failed>(f.progress().replace(f.access, null, invalid))
        }
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(null), f.progress().current(f.access))
    }

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
