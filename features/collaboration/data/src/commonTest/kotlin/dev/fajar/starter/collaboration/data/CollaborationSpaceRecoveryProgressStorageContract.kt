package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceRecoveryProgressRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
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
                    wake: WorkWakeRequest?,
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
                    wake: WorkWakeRequest?,
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
                    original.replace("\"schema\":1", "\"schema\":99"),
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

    private fun messageSweep(f: CollaborationHistoryFixture) =
        sweep(f)
            .copy(
                pending = null,
                messages =
                    CollaborationSpaceMessageRecoverySweep(
                        after =
                            CollaborationSpaceMessageRecoveryPosition(
                                Instant.parse("2026-11-10T00:00:00.000000001Z"),
                                historyId(1001),
                            ),
                        pending =
                            CollaborationSpaceMessageRecoveryClaim(
                                CollaborationSpaceMessageRecoveryPosition(
                                    Instant.parse("2026-11-10T00:00:00.000000002Z"),
                                    historyId(1002),
                                ),
                                historyId(1003),
                            ),
                    ),
            )

    @Test
    fun legacyBindingClaimUpgradesWithoutChangingItsNamespaceOrHistoryKey() = withDatabase { f ->
        val legacy = value(f.progress().replace(f.owner(), null, sweep(f)))
        val key = f.keys.read()
        f.reopen()
        assertEquals(legacy, value(f.progress().current(f.owner())))
        val legacySweep = requireNotNull(legacy.sweep)
        assertNull(legacySweep.messages)
        assertEquals(sweep(f).pending, legacySweep.pending)
        val next = value(f.progress().replace(f.owner(), legacy, messageSweep(f)))
        f.reopen()
        assertEquals(next, value(f.progress().current(f.owner())))
        assertEquals(key, f.keys.read())
        assertEquals(
            "collaboration-history/space-recovery/v1/",
            spaceRecoveryPartition(f.owner()).substringBefore(f.owner().parent.nativeSessionId),
        )
    }

    @Test
    fun exactNanosecondClaimCursorAndExhaustionSurviveReopen() = withDatabase { f ->
        var previous: CollaborationSpaceRecoveryProgress? = null
        val active = messageSweep(f)
        val phase = active.messages!!
        for (next in
            listOf(
                active,
                active.copy(
                    messages = phase.copy(after = phase.pending!!.position, pending = null)
                ),
                active.copy(
                    messages =
                        phase.copy(
                            after = phase.pending!!.position,
                            pending = null,
                            reachedEnd = true,
                        )
                ),
            )) {
            previous = value(f.progress().replace(f.owner(), previous, next))
            f.reopen()
            assertEquals(previous, value(f.progress().current(f.owner())))
        }
    }

    @Test
    fun messageClaimSurvivesLostCommitResponseWithItsOriginalCoordinates() = withDatabase { f ->
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
                    throw CancellationException("Fictional response loss")
                }
            }
        assertFailsWith<CancellationException> {
            f.progress(store).replace(f.owner(), null, messageSweep(f))
        }
        f.reopen()
        val saved = value(f.progress().current(f.owner()))
        assertEquals(messageSweep(f), saved.sweep)
        assertEquals(
            AppResult.Success(null),
            f.progress().replace(f.owner(), null, messageSweep(f)),
        )
    }

    @Test
    fun malformedOrOverlappingMessageClaimsCannotCreateAnyProtectedState() = withDatabase { f ->
        val good = messageSweep(f)
        val phase = good.messages!!
        val claim = phase.pending!!
        for (bad in
            listOf(
                good.copy(pending = sweep(f).pending),
                good.copy(messages = phase.copy(reachedEnd = true)),
                good.copy(messages = phase.copy(after = claim.position)),
                good.copy(messages = phase.copy(pending = claim.copy(conversationId = "invalid"))),
                good.copy(
                    messages =
                        phase.copy(
                            pending =
                                claim.copy(position = claim.position.copy(messageId = "invalid"))
                        )
                ),
            )) assertIs<AppResult.Failed>(f.progress().replace(f.owner(), null, bad))
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(null), f.progress().current(f.owner()))
    }

    @Test
    fun schemaTwoRejectsRelabelingNoncanonicalTimeAndOversizedPlaintext() = withDatabase { f ->
        val first = value(f.progress().replace(f.owner(), null, messageSweep(f)))
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
            assertTrue(original.contains("\"schema\":2"))
            for (bad in
                listOf(
                    original.replace("\"schema\":2", "\"schema\":1"),
                    original.replace("\"schema\":2", "\"schema\":99"),
                    original.replace(".000000002Z", ".000000002+00:00"),
                    original
                        .replace(".000000002Z", ".000000001Z")
                        .replace(historyId(1002), historyId(1001)),
                    original + " ".repeat(4096),
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
        assertEquals(first, value(f.progress().current(f.owner())))
    }

    @Test
    fun messagePhaseCiphertextCannotCrossItsNativeParentOrSpace() = withDatabase { f ->
        val first = value(f.progress().replace(f.owner(), null, messageSweep(f)))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceRecoveryScope(f.owner()),
                    spaceRecoveryPartition(f.owner()),
                )
            )
        for (other in
            listOf(
                f.owner().copy(spaceId = historyId(1100)),
                f.owner().copy(parent = f.owner().parent.copy(nativeSessionId = historyId(1101))),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        raw.copy(handle = raw.handle.copy(scope = scope, partition = partition))
                }
            assertIs<AppResult.Failed>(f.progress(store).current(other))
        }
        assertEquals(first, value(f.progress().current(f.owner())))
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
