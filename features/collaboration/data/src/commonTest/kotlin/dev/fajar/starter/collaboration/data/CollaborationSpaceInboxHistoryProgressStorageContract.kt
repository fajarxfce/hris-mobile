package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceInboxHistoryProgressStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun missingMetadataReadsCreateNoKeysAndNeverReadInboxEntries() = withDatabase { f ->
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> = error("Unexpected entry read")
            }
        assertEquals(AppResult.Success(null), f.repository(store).current(f.access()))
        assertEquals(
            AppResult.Success(null),
            f.repository(store).observeDestination(f.access()).first(),
        )
        assertNull(f.base.keys.read())
        assertFalse(actual.containsRecords(COLLABORATION_HISTORY_PREFIX))
        val version = f.publishIndex(20)
        assertEquals(
            AppResult.Success(version),
            f.repository(store).observeDestination(f.access()).first(),
        )
    }

    @Test
    fun aCapturedPageAndPhysicalVersionSurviveReopenWithoutAnotherRequest() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val queued =
            success(
                captureCollaborationSpaceInboxHistoryBatch(
                    initial.progress,
                    listOf(f.id, historyId(9004)),
                    "opaque-next",
                )
            )
        val next = f.save(f.mutation(initial, queued))
        assertEquals(0L, initial.version.revision)
        assertEquals(1L, next.version.revision)
        assertEquals(initial.version.generation, next.version.generation)
        val key = f.base.keys.read()
        f.base.reopen()
        assertEquals(next, f.current())
        assertEquals(
            AppResult.Success(next),
            f.repository().mutate(f.access(), f.mutation(next, next.progress)),
        )
        assertEquals(key, f.base.keys.read())
        assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        val deferred =
            success(
                acknowledgeCollaborationSpaceInboxHistorySource(
                    f.access(),
                    next.progress,
                    f.id,
                    null,
                    null,
                )
            )
        val retainedFailure = f.save(f.mutation(next, deferred))
        f.base.reopen()
        assertEquals(retainedFailure, f.current())
        assertTrue(assertNotNull(f.current().progress.pass).catalogDeferred)
    }

    @Test
    fun sourceAcknowledgementUsesExactlyOneGuardAndOneGlobalWrite() = withDatabase { f ->
        val source = f.source()
        val first = f.save(f.mutation(null))
        val queued =
            f.save(
                f.mutation(
                    first,
                    success(
                        captureCollaborationSpaceInboxHistoryBatch(
                            first.progress,
                            listOf(f.id),
                            null,
                        )
                    ),
                )
            )
        val proposed =
            success(
                acknowledgeCollaborationSpaceInboxHistorySource(
                    f.access(),
                    queued.progress,
                    f.id,
                    source,
                    source,
                )
            )
        val actual = f.base.database.replicas
        var writes = 0
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    writes++
                    assertEquals(2, mutations.size)
                    assertEquals(
                        spaceInboxHistorySourcePartition(f.access(), f.id),
                        assertIs<ReplicaMutation.Check>(mutations[0]).partition,
                    )
                    assertEquals(
                        spaceInboxHistoryProgressPartition(f.access()),
                        assertIs<ReplicaMutation.Commit>(mutations[1]).handle.partition,
                    )
                    assertNull(wake)
                    return actual.mutate(scope, mutations, wake)
                }
            }
        val saved =
            assertNotNull(
                success(
                    f.repository(store)
                        .mutate(
                            f.access(),
                            CollaborationSpaceInboxHistoryMutation(
                                queued,
                                proposed,
                                CollaborationSpaceInboxHistoryGuard.Source(source),
                            ),
                        )
                )
            )
        assertEquals(proposed, saved.progress)
        assertEquals(source, success(f.sources().current(f.access(), f.id)))
        assertEquals(1, writes)
    }

    @Test
    fun ordinaryIndexAdvancementKeepsTheCapturedDestinationValid() = withDatabase { f ->
        val captured = f.publishIndex(20)
        f.publishIndex(21)
        val saved = f.save(f.mutation(null, f.initial(captured), captured))
        assertEquals(captured.generation, saved.progress.latest.destinationGeneration)
    }

    @Test
    fun destinationReplacementCannotAcknowledgeOldProgress() = withDatabase { f ->
        val captured = f.publishIndex(20)
        val initial = f.save(f.mutation(null, f.initial(captured), captured))
        assertTrue(
            f.base.database.replicas.clear(
                spaceHistoryScope(f.access()),
                spaceInboxPartition(f.access()),
            )
        )
        val replaced = f.publishIndex(21)
        assertNotEquals(captured.generation, replaced.generation)
        val proposal = f.mutation(initial, f.ready(initial.progress), captured)
        assertEquals(AppResult.Success(null), f.repository().mutate(f.access(), proposal))
        assertEquals(initial, f.current())
    }

    @Test
    fun creatingTheIndexDuringAnAbsentGuardCannotCreateGlobalProgress() = withDatabase { f ->
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.publishIndex(20)
                    return actual.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.repository(store).mutate(f.access(), f.mutation(null)),
        )
        assertEquals(AppResult.Success(null), f.repository().current(f.access()))
    }

    @Test
    fun changingASourceAfterInspectionRejectsItsGlobalAcknowledgement() = withDatabase { f ->
        val source = f.source()
        val first = f.save(f.mutation(null))
        val queued =
            f.save(
                f.mutation(
                    first,
                    success(
                        captureCollaborationSpaceInboxHistoryBatch(
                            first.progress,
                            listOf(f.id),
                            null,
                        )
                    ),
                )
            )
        val proposed =
            success(
                acknowledgeCollaborationSpaceInboxHistorySource(
                    f.access(),
                    queued.progress,
                    f.id,
                    source,
                    source,
                )
            )
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertNotNull(
                        success(
                            f.sources()
                                .mutate(
                                    f.access(),
                                    CollaborationSpaceInboxHistorySourceMutation(
                                        source,
                                        source.progress.copy(historyComplete = true),
                                        null,
                                        emptyList(),
                                        emptyList(),
                                    ),
                                )
                        )
                    )
                    return actual.mutate(scope, mutations, wake)
                }
            }
        val mutation =
            CollaborationSpaceInboxHistoryMutation(
                queued,
                proposed,
                CollaborationSpaceInboxHistoryGuard.Source(source),
            )
        assertEquals(AppResult.Success(null), f.repository(store).mutate(f.access(), mutation))
        assertEquals(queued, f.current())
    }

    @Test
    fun replacingDestinationAfterFinalInspectionPreventsCompletion() = withDatabase { f ->
        val destination = f.publishIndex(20)
        val initial = f.save(f.mutation(null, f.initial(destination), destination))
        val ready = f.save(f.mutation(initial, f.ready(initial.progress), destination))
        val completed = success(completeCollaborationSpaceInboxHistory(ready.progress))
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    check(actual.clear(scope, spaceInboxPartition(f.access())))
                    f.publishIndex(21)
                    return actual.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.repository(store).mutate(f.access(), f.mutation(ready, completed, destination)),
        )
        assertEquals(ready, f.current())
        assertNull(f.current().progress.completed)
    }

    @Test
    fun concurrentGlobalWritersKeepOnlyOneExpectedTransition() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val actual = f.base.database.replicas
        val entered = Channel<Unit>(2)
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.send(Unit)
                    release.await()
                    return actual.mutate(scope, mutations, wake)
                }
            }
        coroutineScope {
            val writes =
                listOf(50, 51).map { id ->
                    async {
                        val replacement =
                            success(
                                observeCollaborationSpaceInboxHistory(
                                    initial.progress,
                                    initial.progress.latest.copy(historySignal = historyId(id)),
                                    historyId(60),
                                )
                            )
                        f.repository(store).mutate(f.access(), f.mutation(initial, replacement))
                    }
                }
            withContext(Dispatchers.Default) {
                withTimeout(5000) { repeat(2) { entered.receive() } }
            }
            release.complete(Unit)
            assertEquals(1, writes.awaitAll().count { success(it) != null })
        }
        assertEquals(1L, f.current().version.revision)
    }

    @Test
    fun aLostCommittedResponseIsRecoveredFromTheExactRetainedState() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    check(actual.mutate(scope, mutations, wake))
                    error("Fixture lost committed response")
                }
            }
        val proposal = f.mutation(initial, f.ready(initial.progress))
        assertIs<AppResult.Failed>(f.repository(store).mutate(f.access(), proposal))
        assertEquals(proposal.replacement, f.current().progress)
        assertEquals(AppResult.Success(null), f.repository().mutate(f.access(), proposal))
    }

    @Test
    fun cancellationBeforeAndAfterCommitNeverClaimsAHalfWrittenCheckpoint() = withDatabase { f ->
        for (commit in listOf(false, true)) {
            val initial = f.save(f.mutation(success(f.repository().current(f.access()))))
            val actual = f.base.database.replicas
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                object : ReplicaStore by actual {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        if (commit) check(actual.mutate(scope, mutations, wake))
                        entered.complete(Unit)
                        if (commit) withContext(NonCancellable) { release.await() }
                        else awaitCancellation()
                        return true
                    }
                }
            val proposal = f.mutation(initial, f.ready(initial.progress))
            coroutineScope {
                val task = launch { f.repository(store).mutate(f.access(), proposal) }
                withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
                task.cancel()
                release.complete(Unit)
                task.join()
                assertTrue(task.isCancelled)
            }
            if (commit) assertEquals(proposal.replacement, f.current().progress)
            else assertEquals(initial, f.current())
        }
    }

    @Test
    fun missingKeyAndCorruptCheckpointCannotBeReplacedWithFreshState() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val key = assertNotNull(f.base.keys.read())
        f.base.keys.write(null)
        assertIs<AppResult.Failed>(f.repository().current(f.access()))
        assertIs<AppResult.Failed>(f.repository().mutate(f.access(), f.mutation(initial)))
        assertNull(f.base.keys.read())
        f.base.keys.write(key)
        val raw = assertNotNull(f.raw())
        assertTrue(
            f.base.database.replicas.commit(
                raw.handle,
                raw.revision,
                raw.payload,
                emptyList(),
                true,
            )
        )
        assertIs<AppResult.Failed>(f.repository().current(f.access()))
        assertIs<AppResult.Failed>(f.repository().mutate(f.access(), f.mutation(initial)))
        assertEquals(key, f.base.keys.read())
    }

    @Test
    fun renewalPreservesOwnedProgressAndAnotherAccountCannotReadIt() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val renewed = f.base.access.copy(sessionId = "renewed")
        f.base.reopen(renewed)
        val local = f.access().copy(sessionId = renewed.sessionId)
        assertEquals(AppResult.Success(initial), f.repository().current(local))
        assertEquals(AppResult.Success(null), f.repository().current(f.access()))
        val other = renewed.copy(accountId = historyId(8000))
        f.base.activate(other)
        assertEquals(
            AppResult.Success(null),
            f.repository().current(local.copy(accountId = other.accountId)),
        )
        assertEquals(AppResult.Success(null), f.repository().mutate(local, f.mutation(initial)))
        f.base.activate(renewed)
        assertEquals(AppResult.Success(initial), f.repository().current(local))
    }

    @Test
    fun queueCollectionsAreCapturedBeforeTheFirstStorageSuspension() = withDatabase { f ->
        val initial = f.save(f.mutation(null))
        val ids = mutableListOf(f.id)
        val proposed =
            initial.progress.copy(
                pass =
                    initial.progress.pass!!.copy(
                        traversal =
                            CollaborationSpaceInboxHistoryTraversal.Catalog(
                                null,
                                CollaborationSpaceInboxHistoryBatch(ids, null),
                            )
                    )
            )
        val actual = f.base.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    ids.clear()
                    return actual.checkpoint(scope, partition)
                }
            }
        val saved =
            assertNotNull(
                success(f.repository(store).mutate(f.access(), f.mutation(initial, proposed)))
            )
        assertTrue(ids.isEmpty())
        assertEquals(
            listOf(f.id),
            assertIs<CollaborationSpaceInboxHistoryTraversal.Catalog>(
                    saved.progress.pass?.traversal
                )
                .batch
                ?.remaining,
        )
    }

    private fun withDatabase(block: suspend (InboxHistoryProgressFixture) -> Unit) = runTest {
        val f = InboxHistoryProgressFixture(CollaborationHistoryFixture(databaseOwner()))
        try {
            f.base.activate()
            block(f)
        } finally {
            f.base.close()
        }
    }
}

private class InboxHistoryProgressFixture(val base: CollaborationHistoryFixture) {
    val id = historyId(9002)

    fun access() =
        CollaborationSpaceLocalAccess(
            base.access.sessionId,
            base.access.audience,
            base.access.accountId,
            historyId(9003),
        )

    fun repository(store: ReplicaStore = base.database.replicas) =
        EncryptedCollaborationSpaceInboxHistoryProgressRepository(store, base.cipher, base.keys)

    fun sources() =
        EncryptedCollaborationSpaceInboxHistorySourceRepository(
            base.database.replicas,
            base.cipher,
            base.keys,
        )

    fun initial(destination: CollaborationSpaceInboxVersion? = null) =
        success(
            observeCollaborationSpaceInboxHistory(
                null,
                CollaborationSpaceInboxHistoryTrigger(null, null, null, destination?.generation),
                historyId(40),
            )
        )

    fun mutation(
        expected: CollaborationSpaceInboxHistoryCheckpoint?,
        replacement: CollaborationSpaceInboxHistoryProgress = expected?.progress ?: initial(),
        destination: CollaborationSpaceInboxVersion? = null,
    ) =
        CollaborationSpaceInboxHistoryMutation(
            expected,
            replacement,
            CollaborationSpaceInboxHistoryGuard.Destination(destination),
        )

    fun ready(progress: CollaborationSpaceInboxHistoryProgress) =
        progress.copy(
            pass = progress.pass!!.copy(traversal = CollaborationSpaceInboxHistoryTraversal.Ready)
        )

    suspend fun save(value: CollaborationSpaceInboxHistoryMutation) =
        assertNotNull(success(repository().mutate(access(), value)))

    suspend fun current() = assertNotNull(success(repository().current(access())))

    suspend fun raw() =
        base.database.replicas.checkpoint(
            spaceHistoryScope(access()),
            spaceInboxHistoryProgressPartition(access()),
        )

    suspend fun source(): CollaborationSpaceInboxHistorySourceCheckpoint {
        val value =
            CollaborationSpaceInboxHistorySourceProgress(
                id,
                CollaborationHistoryChangeOwner(
                    access().audience,
                    access().accountId,
                    CollaborationHistoryChangeOwnerKind.COMPANY,
                    base.access.companyId,
                    base.conversation,
                    base.room,
                ),
                null,
                historyId(40),
                null,
                false,
                null,
            )
        return assertNotNull(
            success(
                sources()
                    .mutate(
                        access(),
                        CollaborationSpaceInboxHistorySourceMutation(
                            null,
                            value,
                            null,
                            emptyList(),
                            emptyList(),
                        ),
                    )
            )
        )
    }

    suspend fun publishIndex(index: Int): CollaborationSpaceInboxVersion {
        val inbox =
            EncryptedCollaborationSpaceInboxRepository(
                base.database.replicas,
                base.cipher,
                base.keys,
            )
        val current = success(inbox.conversation(access(), historyId(index), 2))
        assertEquals(
            AppResult.Success(true),
            inbox.publish(
                access(),
                CollaborationSpaceInboxPublication(
                    current.version,
                    current.version?.lastOrdinal ?: 0,
                    collaborationSpaceInboxEntry(historyId(index), 0, null, null),
                ),
            ),
        )
        return assertNotNull(success(inbox.conversation(access(), historyId(index), 2)).version)
    }
}

private fun <T> success(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value
