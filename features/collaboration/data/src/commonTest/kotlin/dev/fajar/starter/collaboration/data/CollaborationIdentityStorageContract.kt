package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.identity.identityPartition
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationIdentityRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationIdentityStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.identities(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationIdentityRepository(store, cipher, keys)

    private val identity = CollaborationSelfIdentity("@fictional:example.test")

    @Test
    fun emptyReadDoesNotGenerateAKeyAndFirstIdentitySurvivesReopenAndReauthentication() =
        withDatabase { f ->
            assertEquals(AppResult.Success(null), f.identities().current(f.access))
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(true), f.identities().retain(f.access, identity))
            val raw =
                assertNotNull(
                    f.database.replicas.checkpoint(
                        historyScope(f.access),
                        identityPartition(f.access),
                    )
                )
            assertFalse(raw.payload.contains(identity.matrixUserId))
            assertFalse(raw.handle.partition.contains(identity.matrixUserId))
            val renewed = f.access.copy(sessionId = "renewed")
            f.reopen(renewed)
            assertEquals(AppResult.Success(identity), f.identities().current(renewed))
            assertEquals(AppResult.Success(true), f.identities().retain(renewed, identity))
            assertEquals(
                raw.payload,
                f.database.replicas
                    .checkpoint(historyScope(renewed), identityPartition(renewed))!!
                    .payload,
            )
        }

    @Test
    fun differentIdentityCannotOverwriteExistingOwnerOrAnotherCompany() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.identities().retain(f.access, identity))
        assertEquals(
            AppResult.Success(false),
            f.identities().retain(f.access, CollaborationSelfIdentity("@other:example.test")),
        )
        val other = f.access.copy(companyId = historyId(999))
        assertEquals(AppResult.Success(null), f.identities().current(other))
        assertEquals(AppResult.Success(identity), f.identities().current(f.access))
    }

    @Test
    fun lostCommitResponseAndCancellationAreRecoveredByExactLocalReplay() = withDatabase { f ->
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    check(f.database.replicas.replace(scope, partition, expected, snapshot, wake))
                    throw CancellationException("Fixture lost response")
                }
            }
        assertFailsWith<CancellationException> { f.identities(store).retain(f.access, identity) }
        f.reopen()
        assertEquals(AppResult.Success(identity), f.identities().current(f.access))
        assertEquals(AppResult.Success(true), f.identities().retain(f.access, identity))
    }

    @Test
    fun competingFirstWritesCannotSubstituteAnotherIdentity() = withDatabase { f ->
        val rival = CollaborationSelfIdentity("@rival:example.test")
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    assertEquals(AppResult.Success(true), f.identities().retain(f.access, rival))
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        assertEquals(AppResult.Success(false), f.identities(store).retain(f.access, identity))
        assertEquals(AppResult.Success(rival), f.identities().current(f.access))
    }

    @Test
    fun changedScopeCheckpointOrCiphertextCannotDiscloseIdentity() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.identities().retain(f.access, identity))
        val original =
            assertNotNull(
                f.database.replicas.checkpoint(historyScope(f.access), identityPartition(f.access))
            )
        for (alteration in 0..4) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint =
                        when (alteration) {
                            0 -> original.copy(payload = "corrupted")
                            1 -> original.copy(revision = original.revision + 1)
                            2 ->
                                original.copy(
                                    handle = original.handle.copy(generation = historyId(800))
                                )
                            3 ->
                                original.copy(
                                    handle =
                                        original.handle.copy(
                                            partition =
                                                identityPartition(
                                                    f.access.copy(companyId = historyId(801))
                                                )
                                        )
                                )
                            else -> original.copy(published = false)
                        }
                }
            assertIs<AppResult.Failed>(f.identities(store).current(f.access))
            assertIs<AppResult.Failed>(f.identities(store).retain(f.access, identity))
        }
    }

    @Test
    fun identityOnlyRecordsAndHistoryBothPreventReplacementOfALostSharedKey() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.identities().retain(f.access, identity))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.identities().current(f.access))
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, listOf(f.event()))
        )
        assertIs<AppResult.Failed>(
            f.identities().retain(f.access.copy(companyId = historyId(802)), identity)
        )
        assertNull(f.keys.read())
    }

    @Test
    fun accountChangeBeforeIdentityPublicationCannotWriteIntoEitherAccount() = withDatabase { f ->
        val other = f.access.copy(sessionId = "other", accountId = historyId(803))
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
        assertEquals(AppResult.Success(false), f.identities(store).retain(f.access, identity))
        assertEquals(AppResult.Success(null), f.identities().current(other))
        f.activate()
        assertEquals(AppResult.Success(null), f.identities().current(f.access))
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
