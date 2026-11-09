package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceReadinessReceiptRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceReadinessReceiptStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun exactLongRevisionsSurviveReopenAndSameFamilySessionReplacement() = withDatabase { f ->
        val scope = f.scope()
        val saved =
            value(
                f.receipts()
                    .replace(
                        scope,
                        null,
                        CollaborationReadinessProgress(9_007_199_254_740_993L, Long.MAX_VALUE),
                    )
            )
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceReadinessReceiptScope(scope),
                    spaceReadinessReceiptPartition(scope),
                )
            )
        assertTrue(raw.payload.length <= 4096)
        assertFalse(raw.payload.contains(Long.MAX_VALUE.toString()))
        val key = f.keys.read()
        f.reopen(f.access.copy(sessionId = "renewed"))
        assertEquals(
            AppResult.Success(saved),
            f.receipts().current(scope.copy(parent = scope.parent.copy(sessionId = "renewed"))),
        )
        assertEquals(key, f.keys.read())
    }

    @Test
    fun deviceOrganizationAndNativeParentPartitionsCannotTransplantCiphertext() =
        withDatabase { f ->
            val scope = f.scope()
            val saved =
                value(f.receipts().replace(scope, null, CollaborationReadinessProgress(1, 2)))
            val raw =
                assertNotNull(
                    f.database.replicas.checkpoint(
                        spaceReadinessReceiptScope(scope),
                        spaceReadinessReceiptPartition(scope),
                    )
                )
            for (other in
                listOf(
                    scope.copy(spaceId = historyId(9101)),
                    scope.copy(deviceId = historyId(9102)),
                    scope.copy(parent = scope.parent.copy(nativeSessionId = historyId(9103))),
                )) {
                assertEquals(AppResult.Success(null), f.receipts().current(other))
                val transplant =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                            raw.copy(handle = raw.handle.copy(scope = scope, partition = partition))
                    }
                assertIs<AppResult.Failed>(f.receipts(transplant).current(other))
            }
            assertEquals(AppResult.Success(saved), f.receipts().current(scope))
        }

    @Test
    fun lateCompletionCannotReplaceNewerPendingRevisionAndConcurrentFirstWritesHaveOneWinner() =
        withDatabase { f ->
            val scope = f.scope()
            val winners =
                coroutineScope {
                        (1..8)
                            .map { index ->
                                async {
                                    f.receipts()
                                        .replace(
                                            scope,
                                            null,
                                            CollaborationReadinessProgress(0, index.toLong()),
                                        )
                                }
                            }
                            .awaitAll()
                    }
                    .map { assertIs<AppResult.Success<CollaborationReadinessReceipt?>>(it).value }
                    .filterNotNull()
            val first = winners.single()
            val newer =
                value(f.receipts().replace(scope, first, CollaborationReadinessProgress(0, 20)))
            assertEquals(
                AppResult.Success(null),
                f.receipts()
                    .replace(
                        scope,
                        first,
                        first.progress.copy(requestedRevision = first.progress.pendingRevision),
                    ),
            )
            f.reopen()
            assertEquals(AppResult.Success(newer), f.receipts().current(scope))
        }

    @Test
    fun processDeathAfterCommitKeepsThePendingReceiptAndSameContentKey() = withDatabase { f ->
        val scope = f.scope()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    check(f.database.replicas.replace(scope, partition, expected, snapshot))
                    throw CancellationException("Fictional process lost after commit")
                }
            }
        assertFailsWith<CancellationException> {
            f.receipts(store).replace(scope, null, CollaborationReadinessProgress(0, 33))
        }
        val key = f.keys.read()
        f.reopen()
        assertEquals(
            CollaborationReadinessProgress(0, 33),
            value(f.receipts().current(scope)).progress,
        )
        assertEquals(key, f.keys.read())
    }

    @Test
    fun accountReplacementDuringCommitDoesNotWriteIntoReplacementAccount() = withDatabase { f ->
        val scope = f.scope()
        val first = value(f.receipts().replace(scope, null, CollaborationReadinessProgress(0, 1)))
        val other = f.access.copy(sessionId = "other", accountId = historyId(9199))
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
            f.receipts(store).replace(scope, first, CollaborationReadinessProgress(1, 1)),
        )
        assertEquals(
            AppResult.Success(null),
            f.receipts()
                .current(
                    scope.copy(
                        parent =
                            scope.parent.copy(
                                sessionId = other.sessionId,
                                accountId = other.accountId,
                            )
                    )
                ),
        )
        f.activate()
        assertEquals(AppResult.Success(first), f.receipts().current(scope))
    }

    @Test
    fun alteredAuthenticatedMetadataCannotBeReadOrAcknowledged() = withDatabase { f ->
        val scope = f.scope()
        val first = value(f.receipts().replace(scope, null, CollaborationReadinessProgress(0, 1)))
        val raw =
            assertNotNull(
                f.database.replicas.checkpoint(
                    spaceReadinessReceiptScope(scope),
                    spaceReadinessReceiptPartition(scope),
                )
            )
        for (mode in 0..5) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        when (mode) {
                            0 -> raw.copy(payload = "invalid")
                            1 -> raw.copy(handle = raw.handle.copy(generation = historyId(9198)))
                            2 -> raw.copy(handle = raw.handle.copy(partition = partition + "other"))
                            3 -> raw.copy(published = false)
                            4 -> raw.copy(revision = 1)
                            else -> raw.copy(payload = "a".repeat(4097))
                        }
                }
            assertIs<AppResult.Failed>(f.receipts(store).current(scope))
            assertIs<AppResult.Failed>(
                f.receipts(store).replace(scope, first, CollaborationReadinessProgress(1, 1))
            )
        }
        assertEquals(AppResult.Success(first), f.receipts().current(scope))
    }

    @Test
    fun invalidProgressCreatesNoKeyAndALostKeyCannotBeReplacedOverInactiveOwnedFacts() =
        withDatabase { f ->
            val validScope = f.scope()
            for (invalidScope in
                listOf(
                    validScope.copy(spaceId = "00000000-0000-0000-0000-000000000000"),
                    validScope.copy(deviceId = ""),
                    validScope.copy(
                        parent =
                            validScope.parent.copy(
                                nativeSessionId = "00000000-0000-0000-0000-000000000000"
                            )
                    ),
                    validScope.copy(
                        parent = validScope.parent.copy(audience = "https://example.test\n")
                    ),
                )) assertIs<AppResult.Failed>(
                f.receipts().replace(invalidScope, null, CollaborationReadinessProgress(0, 1))
            )
            for (progress in
                listOf(
                    CollaborationReadinessProgress(-1, 1),
                    CollaborationReadinessProgress(2, 1),
                    CollaborationReadinessProgress(0, 0),
                )) assertIs<AppResult.Failed>(f.receipts().replace(f.scope(), null, progress))
            assertNull(f.keys.read())
            value(f.receipts().replace(f.scope(), null, CollaborationReadinessProgress(0, 1)))
            f.keys.write(null)
            val other = f.access.copy(sessionId = "other", accountId = historyId(9197))
            f.activate(other)
            val replacement =
                f.scope()
                    .copy(
                        parent =
                            f.scope()
                                .parent
                                .copy(sessionId = other.sessionId, accountId = other.accountId)
                    )
            assertIs<AppResult.Failed>(
                f.receipts().replace(replacement, null, CollaborationReadinessProgress(0, 2))
            )
            assertNull(f.keys.read())
        }

    private fun CollaborationHistoryFixture.scope() =
        CollaborationSpaceReadinessScope(
            CollaborationNativeParent(
                access.sessionId,
                access.audience,
                access.accountId,
                historyId(9100),
            ),
            historyId(9200),
            historyId(9300),
        )

    @Test
    fun companyAndOrganizationCiphertextCannotBeReinterpretedEvenWhenScopeIdsMatch() =
        withDatabase { f ->
            val scope = f.scope()
            val company = CollaborationReadinessScope(scope.parent, scope.spaceId, scope.deviceId)
            val companyRepository =
                dev.fajar.starter.collaboration.data.repositories
                    .EncryptedCollaborationReadinessReceiptRepository(
                        f.database.replicas,
                        f.cipher,
                        f.keys,
                    )
            val groupReceipt =
                value(f.receipts().replace(scope, null, CollaborationReadinessProgress(0, 11)))
            val companyReceipt =
                value(
                    companyRepository.replace(company, null, CollaborationReadinessProgress(0, 77))
                )
            val groupRaw =
                assertNotNull(
                    f.database.replicas.checkpoint(
                        spaceReadinessReceiptScope(scope),
                        spaceReadinessReceiptPartition(scope),
                    )
                )
            val companyRaw =
                assertNotNull(
                    f.database.replicas.checkpoint(
                        readinessReceiptScope(company),
                        readinessReceiptPartition(company),
                    )
                )
            assertNotEquals(groupRaw.handle.partition, companyRaw.handle.partition)
            val companyIntoGroup =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        companyRaw.copy(
                            handle = companyRaw.handle.copy(scope = scope, partition = partition)
                        )
                }
            assertIs<AppResult.Failed>(f.receipts(companyIntoGroup).current(scope))
            val groupIntoCompany =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        groupRaw.copy(
                            handle = groupRaw.handle.copy(scope = scope, partition = partition)
                        )
                }
            assertIs<AppResult.Failed>(
                dev.fajar.starter.collaboration.data.repositories
                    .EncryptedCollaborationReadinessReceiptRepository(
                        groupIntoCompany,
                        f.cipher,
                        f.keys,
                    )
                    .current(company)
            )
            assertEquals(AppResult.Success(groupReceipt), f.receipts().current(scope))
            assertEquals(AppResult.Success(companyReceipt), companyRepository.current(company))
        }

    private fun CollaborationHistoryFixture.receipts(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceReadinessReceiptRepository(store, cipher, keys)

    private fun value(result: AppResult<CollaborationReadinessReceipt?>) =
        assertNotNull(assertIs<AppResult.Success<CollaborationReadinessReceipt?>>(result).value)

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
