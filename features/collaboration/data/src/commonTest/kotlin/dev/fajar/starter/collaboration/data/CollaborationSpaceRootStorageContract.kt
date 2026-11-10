package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceRootRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRootStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private val root = CollaborationSpaceRoot(historyId(9500))

    private fun CollaborationHistoryFixture.access(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceAccountAccess(owner.sessionId, owner.audience, owner.accountId)

    private fun CollaborationHistoryFixture.roots(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceRootRepository(store, cipher, keys)

    @Test
    fun emptyReadsCreateNoKeyAndRootSurvivesRestartAndNewLocalSession() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.roots().current(f.access()))
        assertEquals(AppResult.Success(null), f.roots().observe(f.access()).first())
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(true), f.roots().retain(f.access(), root))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceAccountScope(f.access()),
                    COLLABORATION_SPACE_ROOT_PARTITION,
                )
            )
        assertFalse(raw.payload.contains(root.spaceId))
        assertFalse(raw.handle.partition.contains(root.spaceId))
        val renewed = f.access.copy(sessionId = "renewed", companyId = historyId(9600))
        f.reopen(renewed)
        assertEquals(AppResult.Success(root), f.roots().observe(f.access(renewed)).first())
        assertEquals(AppResult.Success(true), f.roots().retain(f.access(renewed), root))
        assertEquals(
            raw.payload,
            f.database.replicas
                .checkpoint(
                    spaceAccountScope(f.access(renewed)),
                    COLLABORATION_SPACE_ROOT_PARTITION,
                )
                ?.payload,
        )
    }

    @Test
    fun anotherSpaceCannotReplaceTheSavedAccountRoot() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.roots().retain(f.access(), root))
        assertEquals(
            AppResult.Success(false),
            f.roots().retain(f.access(), root.copy(spaceId = historyId(9501))),
        )
        assertEquals(AppResult.Success(root), f.roots().current(f.access()))
        assertEquals(f.access(), f.access(f.access.copy(companyId = historyId(9502))))
    }

    @Test
    fun accountAndServerBoundariesKeepSeparateRoots() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.roots().retain(f.access(), root))
        for (foreign in
            listOf(
                f.access.copy(sessionId = "other", accountId = historyId(9503)),
                f.access.copy(sessionId = "other-server", audience = "https://other.example.test/"),
            )) {
            f.activate(foreign)
            assertEquals(AppResult.Success(null), f.roots().current(f.access(foreign)))
            val otherRoot = root.copy(spaceId = historyId(9504))
            assertEquals(AppResult.Success(true), f.roots().retain(f.access(foreign), otherRoot))
            assertEquals(AppResult.Success(otherRoot), f.roots().current(f.access(foreign)))
            f.activate()
            assertEquals(AppResult.Success(root), f.roots().current(f.access()))
        }
    }

    @Test
    fun competingFirstWritesNeverSubstituteAnotherRoot() = withDatabase { f ->
        val otherRoot = root.copy(spaceId = historyId(9505))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertEquals(AppResult.Success(true), f.roots().retain(f.access(), otherRoot))
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        assertEquals(AppResult.Success(false), f.roots(store).retain(f.access(), root))
        assertEquals(AppResult.Success(otherRoot), f.roots().current(f.access()))
    }

    @Test
    fun lostCommitResponseCanBeReplayedWithoutChangingTheOriginalCiphertext() = withDatabase { f ->
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
        assertFailsWith<CancellationException> { f.roots(store).retain(f.access(), root) }
        f.reopen()
        assertEquals(AppResult.Success(root), f.roots().current(f.access()))
        assertEquals(AppResult.Success(true), f.roots().retain(f.access(), root))
    }

    @Test
    fun rootOnlyCiphertextAlsoFencesLostSharedKeysAcrossInactiveAccounts() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.roots().retain(f.access(), root))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.roots().current(f.access()))
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, f.conversation, f.room, listOf(f.event()))
        )
        val foreign = f.access.copy(sessionId = "other", accountId = historyId(9510))
        f.activate(foreign)
        assertIs<AppResult.Failed>(f.roots().retain(f.access(foreign), root))
        assertNull(f.keys.read())
    }

    @Test
    fun changedCheckpointAndCiphertextCannotDiscloseOrReplaceTheRoot() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.roots().retain(f.access(), root))
        val original =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceAccountScope(f.access()),
                    COLLABORATION_SPACE_ROOT_PARTITION,
                )
            )
        for (changed in
            listOf(
                original.copy(payload = "corrupted"),
                original.copy(payload = "x".repeat(4097)),
                original.copy(revision = 1),
                original.copy(published = false),
                original.copy(handle = original.handle.copy(generation = historyId(9511))),
                original.copy(handle = original.handle.copy(partition = "foreign")),
                original.copy(handle = original.handle.copy(scope = ReplicaScope("foreign"))),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        changed

                    override fun observeCheckpoint(scope: ReplicaScope, partition: String) =
                        flowOf(changed)
                }
            assertIs<AppResult.Failed>(f.roots(store).current(f.access()))
            assertIs<AppResult.Failed>(f.roots(store).observe(f.access()).first())
            assertIs<AppResult.Failed>(f.roots(store).retain(f.access(), root))
        }
    }

    @Test
    fun invalidOwnersFailInsideSafeStorageFlowWithoutCreatingKeys() = withDatabase { f ->
        for (invalid in
            listOf(
                f.access().copy(sessionId = ""),
                f.access().copy(sessionId = "bad\n"),
                f.access().copy(audience = "x".repeat(2049)),
                f.access().copy(accountId = "invalid"),
            )) {
            assertIs<AppResult.Failed>(f.roots().observe(invalid).first())
            assertIs<AppResult.Failed>(f.roots().current(invalid))
            assertIs<AppResult.Failed>(f.roots().retain(invalid, root))
        }
        assertIs<AppResult.Failed>(f.roots().retain(f.access(), CollaborationSpaceRoot("invalid")))
        assertNull(f.keys.read())
    }

    @Test
    fun accountReplacementAtPublicationWritesIntoNeitherAccount() = withDatabase { f ->
        val foreign = f.access.copy(sessionId = "other", accountId = historyId(9512))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(foreign)
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        assertEquals(AppResult.Success(false), f.roots(store).retain(f.access(), root))
        assertEquals(AppResult.Success(null), f.roots().current(f.access(foreign)))
        f.activate()
        assertEquals(AppResult.Success(null), f.roots().current(f.access()))
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
