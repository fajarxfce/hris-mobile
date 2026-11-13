package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.CollaborationComposerCoordinates
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationComposerRecoveryCursorRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceComposerCursorStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absentAndInvalidCursorOperationsNeverCreateKeysOrWork() = withDatabase { f ->
        val cursor = f.cursor()
        assertEquals(AppResult.Success(null), cursor.current(f.space()))
        assertIs<AppResult.Failed>(cursor.current(f.space().copy(spaceId = "invalid")))
        assertIs<AppResult.Failed>(cursor.advance(f.space(), null, "invalid"))
        assertIs<AppResult.Failed>(cursor.advance(f.access, null, "invalid"))
        assertIs<AppResult.Failed>(
            cursor.advance(
                f.space(),
                CollaborationComposerRecoveryCursor("invalid", historyId(1)),
                historyId(2),
            )
        )
        assertNull(f.keys.read())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun retainedPositionSurvivesLoginRenewalAndFencesStaleAdvancement() = withDatabase { f ->
        val access = f.space()
        val first =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                        f.cursor().advance(access, null, historyId(1))
                    )
                    .value
            )
        val key = f.keys.read()
        val renewed = access.copy(sessionId = "renewed")
        f.reopen(f.access.copy(sessionId = renewed.sessionId))
        assertEquals(AppResult.Success(first), f.cursor().current(renewed))
        assertEquals(AppResult.Success(null), f.cursor().advance(renewed, null, historyId(2)))
        val next =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                        f.cursor().advance(renewed, first, historyId(2))
                    )
                    .value
            )
        assertNotEquals(first.generation, next.generation)
        assertEquals(historyId(2), next.afterSubmissionId)
        assertEquals(AppResult.Success(next), f.cursor().current(renewed))
        assertEquals(key, f.keys.read())
    }

    @Test
    fun equalCompanyAndGroupIdsKeepIndependentCursorPositions() = withDatabase { f ->
        val company =
            assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                    f.cursor().advance(f.access, null, historyId(1))
                )
                .value
        val first =
            assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                    f.cursor().advance(f.space(), null, historyId(2))
                )
                .value
        val other = f.space().copy(spaceId = historyId(888))
        val second =
            assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                    f.cursor().advance(other, null, historyId(3))
                )
                .value
        assertNotNull(company)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(AppResult.Success(company), f.cursor().current(f.access))
        assertEquals(AppResult.Success(first), f.cursor().current(f.space()))
        assertEquals(AppResult.Success(second), f.cursor().current(other))
    }

    @Test
    fun concurrentAdvancesRetainExactlyOneWinner() = withDatabase { f ->
        val first =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                        f.cursor().advance(f.space(), null, historyId(1))
                    )
                    .value
            )
        val attempts = coroutineScope {
            listOf(
                    async { f.cursor().advance(f.space(), first, historyId(2)) },
                    async { f.cursor().advance(f.space(), first, historyId(3)) },
                )
                .awaitAll()
        }
        val retained =
            attempts.map {
                assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(it).value
            }
        val winner = retained.filterNotNull().single()
        assertEquals(1, retained.count { it == null })
        assertEquals(AppResult.Success(winner), f.cursor().current(f.space()))
    }

    @Test
    fun lostCommitReplyKeepsTheActualCursorAndDoesNotAllocateAReplacement() = withDatabase { f ->
        var writes = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    val result =
                        f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                    writes++
                    check(!result) { "Simulated lost committed reply" }
                    return result
                }
            }
        assertIs<AppResult.Failed>(f.cursor(store).advance(f.space(), null, historyId(1)))
        val retained =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                        f.cursor().current(f.space())
                    )
                    .value
            )
        assertEquals(historyId(1), retained.afterSubmissionId)
        assertEquals(AppResult.Success(null), f.cursor().advance(f.space(), null, historyId(2)))
        assertEquals(1, writes)
        assertEquals(AppResult.Success(retained), f.cursor().current(f.space()))
    }

    @Test
    fun lostContentKeyCannotBeReplacedOverAnyOwnedGroupCursor() = withDatabase { f ->
        assertNotNull(
            assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                    f.cursor().advance(f.space(), null, historyId(1))
                )
                .value
        )
        val originalKey = assertNotNull(f.keys.read())
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.cursor().current(f.space()))
        assertIs<AppResult.Failed>(
            f.cursor().advance(f.space().copy(spaceId = historyId(888)), null, historyId(2))
        )
        assertIs<AppResult.Failed>(f.cursor().advance(f.access, null, historyId(2)))
        assertNull(f.keys.read())
        f.keys.write(originalKey)
        assertEquals(
            historyId(1),
            assertNotNull(
                    assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                            f.cursor().current(f.space())
                        )
                        .value
                )
                .afterSubmissionId,
        )
    }

    @Test
    fun transplantedCursorCannotOpenUnderAnotherGroupOrCompany() = withDatabase { f ->
        val original =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                        f.cursor().advance(f.space(), null, historyId(1))
                    )
                    .value
            )
        val source = CollaborationComposerCoordinates.Space(f.space())
        val raw =
            assertNotNull(f.database.replicas.checkpoint(source.scope, source.recoveryPartition))
        val other = f.space().copy(spaceId = historyId(888))
        for (target in
            listOf(
                CollaborationComposerCoordinates.Space(other),
                CollaborationComposerCoordinates.Company(f.access),
            )) {
            assertTrue(
                f.database.replicas.replace(
                    target.scope,
                    target.recoveryPartition,
                    null,
                    ReplicaSnapshot(raw.handle.generation, raw.payload, emptyList()),
                )
            )
        }
        assertIs<AppResult.Failed>(f.cursor().current(other))
        assertIs<AppResult.Failed>(f.cursor().current(f.access))
        assertEquals(AppResult.Success(original), f.cursor().current(f.space()))
    }

    @Test
    fun cancellationDuringPublicationKeepsThePreviousPositionWithoutRetrying() = withDatabase { f ->
        val first =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                        f.cursor().advance(f.space(), null, historyId(1))
                    )
                    .value
            )
        val key = f.keys.read()
        val entered = CompletableDeferred<Unit>()
        var writes = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    writes++
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val work = async { f.cursor(store).advance(f.space(), first, historyId(2)) }
            entered.await()
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
        }
        assertEquals(AppResult.Success(first), f.cursor().current(f.space()))
        assertEquals(key, f.keys.read())
        assertEquals(1, writes)
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    private fun CollaborationHistoryFixture.space() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            access.companyId,
        )

    private fun CollaborationHistoryFixture.cursor(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationComposerRecoveryCursorRepository(store, cipher, keys)

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val f = CollaborationHistoryFixture(databaseOwner())
            try {
                f.activate()
                block(f)
            } finally {
                f.close()
            }
        }
    }
}
