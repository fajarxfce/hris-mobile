package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceInboxRepairProgressStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absentAndInvalidReadsCreateNoKeysOrWork() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.progress().current(f.local()))
        assertIs<AppResult.Failed>(f.progress().current(f.local().copy(spaceId = "invalid")))
        assertNull(f.keys.read())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun aCapturedPassAndLaterDemandSurviveRestartWithoutMixingTheirPositions() = withDatabase { f ->
        val captured = f.value()
        assertEquals(AppResult.Success(captured), f.progress().replace(f.local(), null, captured))
        val demanded = captured.copy(generation = historyId(11), latestDemand = historyId(12))
        assertEquals(
            AppResult.Success(demanded),
            f.progress().replace(f.local(), captured, demanded),
        )
        val keys = f.keys.read()
        f.reopen()
        assertEquals(AppResult.Success(demanded), f.progress().current(f.local()))
        val completed =
            demanded.copy(
                generation = historyId(13),
                completedDemand = captured.pass!!.demand,
                pass = null,
            )
        assertEquals(
            AppResult.Success(completed),
            f.progress().replace(f.local(), demanded, completed),
        )
        f.reopen()
        assertEquals(AppResult.Success(completed), f.progress().current(f.local()))
        assertNotEquals(completed.latestDemand, completed.completedDemand)
        assertEquals(keys, f.keys.read())
    }

    @Test
    fun acknowledgedIdleProgressAndExactReplayDoNotRewriteCiphertextOrScheduleWork() =
        withDatabase { f ->
            val done = f.value().let { it.copy(completedDemand = it.latestDemand, pass = null) }
            assertEquals(AppResult.Success(done), f.progress().replace(f.local(), null, done))
            val before = f.repairProgressCheckpoint()
            assertEquals(AppResult.Success(done), f.progress().replace(f.local(), done, done))
            assertEquals(before, f.repairProgressCheckpoint())
            f.reopen()
            assertEquals(AppResult.Success(done), f.progress().current(f.local()))
            assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        }

    @Test
    fun aStaleWriterCannotOverwriteNewDemandOrReuseTheCurrentGeneration() = withDatabase { f ->
        val captured = f.value()
        assertEquals(AppResult.Success(captured), f.progress().replace(f.local(), null, captured))
        val newer = captured.copy(generation = historyId(11), latestDemand = historyId(12))
        assertEquals(AppResult.Success(newer), f.progress().replace(f.local(), captured, newer))
        assertEquals(
            AppResult.Success(null),
            f.progress()
                .replace(
                    f.local(),
                    captured,
                    captured.copy(generation = historyId(13), pass = null),
                ),
        )
        assertIs<AppResult.Failed>(f.progress().replace(f.local(), newer, newer.copy(pass = null)))
        assertEquals(AppResult.Success(newer), f.progress().current(f.local()))
    }

    @Test
    fun concurrentCheckpointWritersPublishExactlyOneReplacement() = withDatabase { f ->
        val captured = f.value()
        assertEquals(AppResult.Success(captured), f.progress().replace(f.local(), null, captured))
        val ready = CompletableDeferred<Unit>()
        val arrivals = Channel<Unit>(capacity = 2)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    arrivals.send(Unit)
                    ready.await()
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        val replacements =
            listOf(
                captured.copy(generation = historyId(11), latestDemand = historyId(12)),
                captured.copy(generation = historyId(13), latestDemand = historyId(14)),
            )
        val results =
            coroutineScope {
                    val jobs =
                        replacements.map { next ->
                            async { f.progress(store).replace(f.local(), captured, next) }
                        }
                    repeat(2) { arrivals.receive() }
                    ready.complete(Unit)
                    jobs.awaitAll()
                }
                .map {
                    assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(it).value
                }
        assertEquals(1, results.count { it != null })
        assertEquals(1, results.count { it == null })
        assertEquals(
            AppResult.Success(results.filterNotNull().single()),
            f.progress().current(f.local()),
        )
    }

    @Test
    fun aLostCommittedResponseRemainsReadableAndTheOldExpectedStateCannotReplayIt() =
        withDatabase { f ->
            val captured = f.value()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertTrue(
                            f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                        )
                        error("Simulated lost committed response")
                    }
                }
            assertIs<AppResult.Failed>(f.progress(store).replace(f.local(), null, captured))
            f.reopen()
            assertEquals(AppResult.Success(captured), f.progress().current(f.local()))
            assertEquals(AppResult.Success(null), f.progress().replace(f.local(), null, captured))
            assertEquals(
                AppResult.Success(captured),
                f.progress().replace(f.local(), captured, captured),
            )
        }

    @Test
    fun cancellationBeforeTheRawCommitRetainsTheOriginalPass() = withDatabase { f ->
        val captured = f.value()
        assertEquals(AppResult.Success(captured), f.progress().replace(f.local(), null, captured))
        val entered = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = async {
                f.progress(store)
                    .replace(
                        f.local(),
                        captured,
                        captured.copy(generation = historyId(11), pass = null),
                    )
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        f.reopen()
        assertEquals(AppResult.Success(captured), f.progress().current(f.local()))
    }

    @Test
    fun renewalRetainsOwnedProgressWhileReplacementAccountsFenceReadsAndWrites() =
        withDatabase { f ->
            val original = f.value()
            assertEquals(
                AppResult.Success(original),
                f.progress().replace(f.local(), null, original),
            )
            val renewed = f.local().copy(sessionId = "renewed")
            f.reopen(f.access.copy(sessionId = renewed.sessionId))
            assertEquals(AppResult.Success(original), f.progress().current(renewed))
            assertEquals(AppResult.Success(null), f.progress().current(f.local()))
            val other = f.access.copy(sessionId = "replacement", accountId = historyId(500))
            f.activate(other)
            assertEquals(AppResult.Success(null), f.progress().current(renewed))
            assertEquals(
                AppResult.Success(null),
                f.progress().replace(renewed, original, original.copy(generation = historyId(11))),
            )
            assertEquals(
                AppResult.Success(null),
                f.progress()
                    .current(renewed.copy(sessionId = other.sessionId, accountId = other.accountId)),
            )
            f.activate(f.access.copy(sessionId = renewed.sessionId))
            assertEquals(AppResult.Success(original), f.progress().current(renewed))
        }

    @Test
    fun equalDemandIdsInDifferentGroupsRemainIndependentAndTheirCiphertextCannotBeMoved() =
        withDatabase { f ->
            val original = f.value()
            val other = f.local().copy(spaceId = historyId(500))
            assertEquals(
                AppResult.Success(original),
                f.progress().replace(f.local(), null, original),
            )
            assertEquals(AppResult.Success(null), f.progress().current(other))
            val raw = assertNotNull(f.repairProgressCheckpoint())
            assertTrue(
                f.database.replicas.replace(
                    spaceHistoryScope(other),
                    spaceInboxRepairProgressPartition(other),
                    null,
                    ReplicaSnapshot(raw.handle.generation, raw.payload, emptyList()),
                )
            )
            assertIs<AppResult.Failed>(f.progress().current(other))
            assertEquals(AppResult.Success(original), f.progress().current(f.local()))
        }

    private fun CollaborationHistoryFixture.local() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            historyId(9003),
        )

    private fun CollaborationHistoryFixture.progress(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceInboxRepairProgressRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.value() =
        CollaborationSpaceInboxRepairProgress(
            historyId(1),
            historyId(2),
            null,
            CollaborationSpaceInboxRepairPass(historyId(2), "opaque-captured-page"),
        )

    private suspend fun CollaborationHistoryFixture.repairProgressCheckpoint() =
        database.replicas.checkpoint(
            spaceHistoryScope(local()),
            spaceInboxRepairProgressPartition(local()),
        )

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
