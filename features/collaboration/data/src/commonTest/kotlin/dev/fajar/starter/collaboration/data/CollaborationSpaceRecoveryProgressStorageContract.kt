package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceRecoveryProgressRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

abstract class CollaborationSpaceRecoveryProgressStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.owner() =
        CollaborationSpaceOwner(
            CollaborationNativeParent(
                access.sessionId,
                access.audience,
                access.accountId,
                historyId(400),
            ),
            historyId(401),
        )

    private fun CollaborationHistoryFixture.progress(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceRecoveryProgressRepository(store, cipher, keys)

    private fun sweep(f: CollaborationHistoryFixture) =
        CollaborationSpaceRecoverySweep(
            historyId(500),
            CollaborationSpaceRoomBindingBoundary(
                f.access.audience,
                f.access.accountId,
                f.owner().spaceId,
                historyId(501),
                7,
            ),
            afterId = "b-" + "1".repeat(64),
            pending = CollaborationSpaceRecoveryClaim(historyId(502), "b-" + "2".repeat(64)),
        )

    private fun value(result: AppResult<CollaborationSpaceRecoveryProgress?>) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceRecoveryProgress?>>(result).value
        )

    @Test
    fun encryptedClaimSurvivesReopenAndSameAccountLoginReplacement() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.progress().current(f.owner()))
        assertNull(f.keys.read())
        val first = value(f.progress().replace(f.owner(), null, sweep(f)))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceRecoveryScope(f.owner()),
                    spaceRecoveryPartition(f.owner()),
                )
            )
        assertFalse(raw.payload.contains(historyId(502)))
        assertFalse(raw.payload.contains(first.generation))
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        val owner = f.owner().copy(parent = f.owner().parent.copy(sessionId = renewed.sessionId))
        assertEquals(AppResult.Success(first), f.progress().current(owner))
        assertEquals(AppResult.Success(null), f.progress().replace(owner, null, sweep(f)))
    }

    @Test
    fun cursorExhaustionAndIndependentRetryReasonsRoundTripExactly() = withDatabase { f ->
        for (mode in 0..3) {
            val old =
                assertIs<AppResult.Success<CollaborationSpaceRecoveryProgress?>>(
                        f.progress().current(f.owner())
                    )
                    .value
            val next =
                sweep(f)
                    .copy(
                        pending = null,
                        reachedEnd = true,
                        retryRequired = mode == 0,
                        continuationRequired = mode == 1,
                        hasNewerBindings = mode == 2,
                    )
            val saved = value(f.progress().replace(f.owner(), old, next))
            f.reopen()
            assertEquals(AppResult.Success(saved), f.progress().current(f.owner()))
        }
    }

    @Test
    fun separateNativeFamiliesAndSpacesCannotReadOrReplaceEachOther() = withDatabase { f ->
        val first = value(f.progress().replace(f.owner(), null, sweep(f)))
        for (other in
            listOf(
                f.owner().copy(spaceId = historyId(700)),
                f.owner().copy(parent = f.owner().parent.copy(nativeSessionId = historyId(701))),
            )) {
            assertEquals(AppResult.Success(null), f.progress().current(other))
            assertEquals(
                AppResult.Success(null),
                f.progress().replace(other, first.copy(sweep = null), null),
            )
        }
        assertEquals(AppResult.Success(first), f.progress().current(f.owner()))
    }

    @Test
    fun competingIndependentRepositoriesHaveOnlyOneCommittedClaim() = withDatabase { f ->
        val results = coroutineScope {
            (1..12)
                .map { n ->
                    async {
                        f.progress()
                            .replace(
                                f.owner(),
                                null,
                                sweep(f).copy(wakeGeneration = historyId(800 + n)),
                            )
                    }
                }
                .awaitAll()
        }
        val committed =
            results
                .map { assertIs<AppResult.Success<CollaborationSpaceRecoveryProgress?>>(it).value }
                .filterNotNull()
        assertEquals(1, committed.size)
        assertEquals(AppResult.Success(committed.single()), f.progress().current(f.owner()))
    }

    @Test
    fun completedAndRestartedSweepNeverAcceptsAnOldGeneration() = withDatabase { f ->
        val first = value(f.progress().replace(f.owner(), null, sweep(f)))
        val done = value(f.progress().replace(f.owner(), first, null))
        val again = value(f.progress().replace(f.owner(), done, sweep(f)))
        assertNotEquals(first.generation, again.generation)
        assertEquals(AppResult.Success(null), f.progress().replace(f.owner(), first, null))
        assertEquals(AppResult.Success(again), f.progress().current(f.owner()))
    }

    @Test
    fun lostCommitResponseKeepsTheOriginalUncertainClaimAfterReopen() = withDatabase { f ->
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
        assertFailsWith<CancellationException> {
            f.progress(store).replace(f.owner(), null, sweep(f))
        }
        f.reopen()
        assertEquals(sweep(f), value(f.progress().current(f.owner())).sweep)
    }

    @Test
    fun ownerReplacementDuringCommitCannotPublishIntoEitherAccount() = withDatabase { f ->
        val owner = f.owner()
        val first = value(f.progress().replace(owner, null, sweep(f)))
        val other = f.access.copy(sessionId = "other", accountId = historyId(900))
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
        assertEquals(AppResult.Success(null), f.progress(store).replace(owner, first, null))
        assertEquals(
            AppResult.Success(null),
            f.progress()
                .current(
                    owner.copy(
                        parent =
                            owner.parent.copy(
                                sessionId = other.sessionId,
                                accountId = other.accountId,
                            )
                    )
                ),
        )
        f.activate()
        assertEquals(AppResult.Success(first), f.progress().current(owner))
    }

    @Test
    fun aLostHistoryKeyIsNeverReplacedToRecoverProgress() = withDatabase { f ->
        value(f.progress().replace(f.owner(), null, sweep(f)))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.progress().current(f.owner()))
        assertIs<AppResult.Failed>(
            f.progress().replace(f.owner().copy(spaceId = historyId(901)), null, null)
        )
        assertNull(f.keys.read())
    }

    @Test
    fun tamperedOwnerGenerationPartitionRevisionOrPayloadCannotClearProgress() = withDatabase { f ->
        val first = value(f.progress().replace(f.owner(), null, sweep(f)))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceRecoveryScope(f.owner()),
                    spaceRecoveryPartition(f.owner()),
                )
            )
        for (mode in 0..7) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint =
                        when (mode) {
                            0 -> raw.copy(payload = "invalid")
                            1 -> raw.copy(handle = raw.handle.copy(generation = historyId(902)))
                            2 ->
                                raw.copy(
                                    handle =
                                        raw.handle.copy(scope = scope.copy(accountKey = "foreign"))
                                )
                            3 ->
                                raw.copy(
                                    handle = raw.handle.copy(partition = COMPANY_RECOVERY_PARTITION)
                                )
                            4 -> raw.copy(published = false)
                            5 -> raw.copy(revision = 1)
                            6 -> raw.copy(payload = "a".repeat(8193))
                            else ->
                                raw.copy(
                                    handle =
                                        raw.handle.copy(
                                            partition =
                                                spaceRecoveryPartition(
                                                    f.owner().copy(spaceId = historyId(903))
                                                )
                                        )
                                )
                        }
                }
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(f.progress(store).current(f.owner())).failure.kind,
            )
            assertIs<AppResult.Failed>(f.progress(store).replace(f.owner(), first, null))
        }
        assertEquals(AppResult.Success(first), f.progress().current(f.owner()))
    }

    @Test
    fun relabeledCiphertextCannotCrossNativeFamilyOrSpaceBoundaries() = withDatabase { f ->
        value(f.progress().replace(f.owner(), null, sweep(f)))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceRecoveryScope(f.owner()),
                    spaceRecoveryPartition(f.owner()),
                )
            )
        for (other in
            listOf(
                f.owner().copy(spaceId = historyId(910)),
                f.owner().copy(parent = f.owner().parent.copy(nativeSessionId = historyId(911))),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        raw.copy(handle = raw.handle.copy(scope = scope, partition = partition))
                }
            assertIs<AppResult.Failed>(f.progress(store).current(other))
        }
    }

    @Test
    fun invalidClaimsCannotCreateAKeyOrCheckpoint() = withDatabase { f ->
        for (bad in
            listOf(
                sweep(f).copy(wakeGeneration = "bad"),
                sweep(f).copy(boundary = null),
                sweep(f).copy(reachedEnd = true),
                sweep(f)
                    .copy(
                        pending = CollaborationSpaceRecoveryClaim(historyId(502), sweep(f).afterId)
                    ),
                sweep(f).copy(boundary = sweep(f).boundary!!.copy(spaceId = historyId(904))),
            )) {
            assertIs<AppResult.Failed>(f.progress().replace(f.owner(), null, bad))
        }
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(null), f.progress().current(f.owner()))
    }

    @Test
    fun unknownSchemaAndNoncanonicalCursorRevisionCannotPublishCompletion() = withDatabase { f ->
        val first = value(f.progress().replace(f.owner(), null, sweep(f)))
        val scope = spaceRecoveryScope(f.owner())
        val partition = spaceRecoveryPartition(f.owner())
        val raw = assertNotNull(f.database.replicas.checkpoint(scope, partition))
        val aad =
            Json.encodeToString(
                    listOf(
                        "hris-collaboration-space-recovery-v1",
                        f.access.audience,
                        f.access.accountId,
                        f.owner().parent.nativeSessionId,
                        f.owner().spaceId,
                        raw.handle.generation,
                        "0",
                    )
                )
                .encodeToByteArray()
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, _ ->
            val bytes = f.cipher.decrypt(key, aad, Base64.decode(raw.payload))
            val original =
                try {
                    bytes.decodeToString()
                } finally {
                    bytes.fill(0)
                }
            for (bad in
                listOf(
                    original.replace("\"schema\":1", "\"schema\":2"),
                    original.replace("\"revision\":\"7\"", "\"revision\":\"07\""),
                )) {
                assertNotEquals(original, bad)
                val payload = Base64.encode(f.cipher.encrypt(key, aad, bad.encodeToByteArray()))
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                            raw.copy(payload = payload)
                    }
                assertIs<AppResult.Failed>(f.progress(store).current(f.owner()))
                assertIs<AppResult.Failed>(f.progress(store).replace(f.owner(), first, null))
            }
        }
        assertEquals(AppResult.Success(first), f.progress().current(f.owner()))
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
