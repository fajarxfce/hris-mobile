package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationRecoveryProgressRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationRecoveryProgressStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.access() =
        CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)

    private fun CollaborationHistoryFixture.progress(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationRecoveryProgressRepository(store, cipher, keys)

    private fun sweep() = CollaborationRecoverySweep(historyId(500), historyId(501), historyId(501))

    private fun value(result: AppResult<CollaborationRecoveryProgress?>) =
        assertNotNull(assertIs<AppResult.Success<CollaborationRecoveryProgress?>>(result).value)

    @Test
    fun encryptedAttemptSurvivesRestartAndSameAccountReauthentication() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.progress().current(f.access()))
        assertNull(f.keys.read())
        val first = value(f.progress().replace(f.access(), null, sweep()))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    recoveryScope(f.access()),
                    COMPANY_RECOVERY_PARTITION,
                )
            )
        assertFalse(raw.payload.contains(sweep().wakeGeneration))
        assertFalse(raw.payload.contains(sweep().afterCompanyId!!))
        assertFalse(raw.handle.partition.contains(sweep().afterCompanyId!!))
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        val access =
            CollaborationRecoveryAccess(renewed.sessionId, renewed.audience, renewed.accountId)
        assertEquals(AppResult.Success(first), f.progress().current(access))
        assertEquals(AppResult.Success(null), f.progress().replace(access, null, sweep()))
    }

    @Test
    fun activeToInactiveToActiveCannotReuseAnOldGeneration() = withDatabase { f ->
        val first = value(f.progress().replace(f.access(), null, sweep()))
        val inactive = value(f.progress().replace(f.access(), first, null))
        val again = value(f.progress().replace(f.access(), inactive, sweep()))
        assertNotEquals(first.generation, again.generation)
        assertEquals(AppResult.Success(null), f.progress().replace(f.access(), first, null))
        assertEquals(AppResult.Success(again), f.progress().current(f.access()))
    }

    @Test
    fun competingRepositoriesCannotOverwriteTheFirstCommittedSweep() = withDatabase { f ->
        val results = coroutineScope {
            (1..16)
                .map { index ->
                    async {
                        f.progress()
                            .replace(
                                f.access(),
                                null,
                                sweep().copy(wakeGeneration = historyId(600 + index)),
                            )
                    }
                }
                .awaitAll()
        }
        val successes =
            results
                .map { assertIs<AppResult.Success<CollaborationRecoveryProgress?>>(it).value }
                .filterNotNull()
        assertEquals(1, successes.size)
        assertEquals(AppResult.Success(successes.single()), f.progress().current(f.access()))
    }

    @Test
    fun cancellationAfterCommitRetainsTheUncertainAttemptAcrossReopen() = withDatabase { f ->
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
            f.progress(store).replace(f.access(), null, sweep())
        }
        f.reopen()
        assertEquals(sweep(), value(f.progress().current(f.access())).sweep)
    }

    @Test
    fun tamperingCannotChangeTheSweepItsOwnerOrCompletion() = withDatabase { f ->
        val first = value(f.progress().replace(f.access(), null, sweep()))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    recoveryScope(f.access()),
                    COMPANY_RECOVERY_PARTITION,
                )
            )
        for (mode in 0..6) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint =
                        when (mode) {
                            0 -> raw.copy(payload = "invalid")
                            1 -> raw.copy(handle = raw.handle.copy(generation = historyId(700)))
                            2 ->
                                raw.copy(
                                    handle =
                                        raw.handle.copy(
                                            scope = scope.copy(accountKey = "foreign-account")
                                        )
                                )
                            3 ->
                                raw.copy(
                                    handle =
                                        raw.handle.copy(partition = "collaboration-history/foreign")
                                )
                            4 -> raw.copy(published = false)
                            5 -> raw.copy(revision = 1)
                            else -> raw.copy(payload = "a".repeat(2049))
                        }
                }
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(f.progress(store).current(f.access())).failure.kind,
            )
            assertIs<AppResult.Failed>(f.progress(store).replace(f.access(), first, null))
        }
        assertEquals(AppResult.Success(first), f.progress().current(f.access()))
    }

    @Test
    fun recoveryOnlyDataPreventsReplacingALostProtectedHistoryKey() = withDatabase { f ->
        value(f.progress().replace(f.access(), null, sweep()))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.progress().current(f.access()))
        val other = f.access.copy(sessionId = "other", accountId = historyId(701))
        f.activate(other)
        val access = CollaborationRecoveryAccess(other.sessionId, other.audience, other.accountId)
        assertIs<AppResult.Failed>(f.progress().replace(access, null, sweep()))
        assertNull(f.keys.read())
    }

    @Test
    fun accountReplacementDuringCommitCannotPublishIntoEitherAccount() = withDatabase { f ->
        val first = value(f.progress().replace(f.access(), null, sweep()))
        val other = f.access.copy(sessionId = "other", accountId = historyId(702))
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
        assertEquals(AppResult.Success(null), f.progress(store).replace(f.access(), first, null))
        assertEquals(
            AppResult.Success(null),
            f.progress()
                .current(
                    CollaborationRecoveryAccess(other.sessionId, other.audience, other.accountId)
                ),
        )
        f.activate()
        assertEquals(AppResult.Success(first), f.progress().current(f.access()))
    }

    @Test
    fun invalidMarkersCannotCreateKeysOrProgress() = withDatabase { f ->
        for (invalid in
            listOf(
                sweep().copy(wakeGeneration = "invalid"),
                sweep().copy(afterCompanyId = null),
                sweep().copy(inFlightCompanyId = historyId(703)),
            )) {
            assertIs<AppResult.Failed>(f.progress().replace(f.access(), null, invalid))
        }
        assertNull(f.keys.read())
        assertEquals(AppResult.Success(null), f.progress().current(f.access()))
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
