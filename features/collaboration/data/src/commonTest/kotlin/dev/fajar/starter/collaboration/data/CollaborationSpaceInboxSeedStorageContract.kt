package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceInboxSeedStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun anEmptyProbeCreatesNeitherKeyNorProjectionOrDemand() = withDatabase { f ->
        val proposal = f.proposal()
        val foreign =
            f.record.intent.copy(owner = f.access.messageOwner().copy(accountId = historyId(800)))
        assertIs<AppResult.Failed>(
            f.inbox()
                .seed(
                    f.access,
                    proposal.copy(repair = CollaborationSpaceInboxRepairChange.Retain(foreign)),
                    null,
                )
        )
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED),
            f.inbox().seed(f.access, proposal, null),
        )
        assertNull(f.base.keys.read())
        assertNull(f.snapshot().checkpoint)
        assertNull(success(f.progress().current(f.access)))
        assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun theOriginalReferenceAndFreshDemandCommitTogetherAndSurviveReopening() = withDatabase { f ->
        val proposal = f.proposal()
        val repair = f.request()
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.STORED),
            f.inbox().seed(f.access, proposal, repair),
        )
        val index = f.snapshot()
        f.base.reopen()
        assertEquals(index, f.snapshot())
        assertEquals(repair.replacement, success(f.progress().current(f.access)))
        assertEquals(f.record.intent, f.references().single().intent)
        assertEquals(
            f.record.intent.text,
            success(f.inbox().conversation(f.access, f.base.conversation))
                .entry
                ?.newest
                ?.preview
                ?.text,
        )
        assertNull(
            success(
                EncryptedCollaborationDraftRepository(
                        f.base.database.replicas,
                        f.base.cipher,
                        f.base.keys,
                    )
                    .get(f.access, f.base.conversation)
            )
        )
        assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun exactAndCompletedReplaysNeverReadOrRewriteLaterRepairProgress() = withDatabase { f ->
        f.seed()
        val reference = f.references().single()
        val original = success(f.inbox().conversation(f.access, f.base.conversation))
        val anchor = original.candidates.single()
        val sent =
            anchor.copy(
                preview = anchor.preview!!.copy(delivery = CollaborationMessageDelivery.SENT)
            )
        assertEquals(
            AppResult.Success(true),
            f.inbox()
                .publish(
                    f.access,
                    CollaborationSpaceInboxPublication(
                        original.version,
                        original.version!!.lastOrdinal,
                        collaborationSpaceInboxEntry(
                            f.base.conversation,
                            original.entry!!.createdAt,
                            original.draft,
                            sent,
                        ),
                        activities = listOf(sent),
                        repair = CollaborationSpaceInboxRepairChange.Complete(reference),
                    ),
                ),
        )
        assertTrue(f.references().isEmpty())
        val progress = success(f.progress().current(f.access))!!
        val later = progress.copy(generation = historyId(601), latestDemand = historyId(602))
        assertEquals(AppResult.Success(later), f.progress().replace(f.access, progress, later))
        val before = f.snapshot()
        val store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    check(partition != spaceInboxRepairProgressPartition(f.access)) {
                        "Replay read progress"
                    }
                    return f.base.database.replicas.checkpoint(scope, partition)
                }

                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean = error("Replay changed storage")
            }
        val replay = f.proposal()
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.RETAINED),
            f.inbox(store).seed(f.access, replay, null),
        )
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.RETAINED),
            f.inbox(store).seed(f.access, replay, f.request(expected = null)),
        )
        assertEquals(before, f.snapshot())
        assertEquals(later, success(f.progress().current(f.access)))
        assertTrue(f.references().isEmpty())
    }

    @Test
    fun aConcurrentDemandWriteRollsBackTheWholeTwoPartitionSeed() = withDatabase { f ->
        val proposal = f.proposal()
        val prepared = f.request()
        val competing = f.request(20).replacement
        val store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertEquals(2, mutations.size)
                    assertEquals(
                        AppResult.Success(competing),
                        f.progress().replace(f.access, null, competing),
                    )
                    return f.base.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.CHANGED),
            f.inbox(store).seed(f.access, proposal, prepared),
        )
        assertNull(f.snapshot().checkpoint)
        assertTrue(f.references().isEmpty())
        assertEquals(competing, success(f.progress().current(f.access)))
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.STORED),
            f.inbox().seed(f.access, f.proposal(), f.request(30)),
        )
    }

    @Test
    fun aConcurrentIndexWriteCannotBeOverwrittenOrPublishThePreparedDemand() = withDatabase { f ->
        val first = f.proposal()
        val request = f.request()
        val competitor =
            f.record.copy(
                intent = f.record.intent.copy(id = historyId(999), text = "Other original")
            )
        val store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    val other = f.proposal(competitor)
                    assertEquals(AppResult.Success(true), f.inbox().publish(f.access, other))
                    return f.base.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.CHANGED),
            f.inbox(store).seed(f.access, first, request),
        )
        assertEquals(listOf(competitor.intent), f.references().map { it.intent })
        assertNull(success(f.progress().current(f.access)))
        assertEquals(
            "Other original",
            success(f.inbox().conversation(f.access, f.base.conversation))
                .entry
                ?.newest
                ?.preview
                ?.text,
        )
    }

    @Test
    fun aLostCommittedResponseRemainsRecoverableWithoutAnotherMutation() = withDatabase { f ->
        val proposal = f.proposal()
        val request = f.request()
        val store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertTrue(f.base.database.replicas.mutate(scope, mutations, wake))
                    throw CancellationException("Seed response lost after commit")
                }
            }
        assertFailsWith<CancellationException> { f.inbox(store).seed(f.access, proposal, request) }
        f.base.reopen()
        assertEquals(request.replacement, success(f.progress().current(f.access)))
        assertEquals(f.record.intent, f.references().single().intent)
        val before = f.snapshot()
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.RETAINED),
            f.inbox().seed(f.access, f.proposal(), null),
        )
        assertEquals(before, f.snapshot())
    }

    @Test
    fun cancellationBeforeTheTransactionLeavesBothPartitionsUnchanged() = withDatabase { f ->
        val proposal = f.proposal()
        val request = f.request()
        val entered = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = async { f.inbox(store).seed(f.access, proposal, request) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertNull(f.snapshot().checkpoint)
        assertNull(success(f.progress().current(f.access)))
    }

    @Test
    fun missingRetainedHistoryKeysCannotBeReplacedOverEitherPartition() = withDatabase { f ->
        val proposal = f.proposal()
        val repair = f.request()
        assertEquals(
            AppResult.Success(repair.replacement),
            f.progress().replace(f.access, null, repair.replacement),
        )
        val saved = assertNotNull(f.base.keys.read())
        f.base.keys.write(null)
        assertNull(f.base.keys.read())
        assertIs<AppResult.Failed>(
            f.inbox().seed(f.access, proposal, f.request(expected = repair.replacement))
        )
        assertNull(f.base.keys.read())
        f.base.keys.write(saved)
        assertEquals(saved, f.base.keys.read())
        f.seed()
        val replay = f.proposal()
        val before = f.snapshot()
        assertNotNull(f.base.keys.read())
        f.base.keys.write(null)
        assertIs<AppResult.Failed>(f.inbox().seed(f.access, replay, null))
        assertNull(f.base.keys.read())
        assertEquals(before, f.snapshot())
    }

    @Test
    fun ongoingIndexAndDemandObserversReceiveTheSameCommittedSeed() = withDatabase { f ->
        val request = f.request()
        coroutineScope {
            val indexReady = CompletableDeferred<Unit>()
            val progressReady = CompletableDeferred<Unit>()
            val index =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        f.inbox()
                            .observePage(f.access)
                            .onEach { indexReady.complete(Unit) }
                            .first {
                                success(it).let { page ->
                                    page is CollaborationSpaceInboxPage.Items &&
                                        page.entries.isNotEmpty()
                                }
                            }
                    }
                }
            val progress =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        f.progress()
                            .observe(f.access)
                            .onEach { progressReady.complete(Unit) }
                            .first { success(it) == request.replacement }
                    }
                }
            indexReady.await()
            progressReady.await()
            assertEquals(
                AppResult.Success(CollaborationSpaceInboxSeedResult.STORED),
                f.inbox().seed(f.access, f.proposal(), request),
            )
            assertEquals(
                f.base.conversation,
                assertIs<CollaborationSpaceInboxPage.Items>(success(index.await()))
                    .entries
                    .single()
                    .conversationId,
            )
            assertEquals(request.replacement, success(progress.await()))
            assertEquals(f.record.intent, f.references().single().intent)
        }
    }

    private fun withDatabase(block: suspend (SeedStorageFixture) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val base = CollaborationHistoryFixture(databaseOwner())
            try {
                base.activate()
                block(SeedStorageFixture(base))
            } finally {
                base.close()
            }
        }
    }
}

private class SeedStorageFixture(val base: CollaborationHistoryFixture) {
    val access =
        CollaborationSpaceLocalAccess(
            base.access.sessionId,
            base.access.audience,
            base.access.accountId,
            base.access.companyId,
        )
    val metadata =
        CollaborationSpaceConversationMetadata(
            access.spaceId,
            base.conversation,
            access.accountId,
            historyId(9003),
            access.accountId,
            Instant.fromEpochMilliseconds(0),
            0,
        )
    val record =
        CollaborationSpaceMessageRecord(
            CollaborationSpaceMessageIntent(
                historyId(600),
                access.messageOwner(),
                base.conversation,
                Instant.parse("2026-11-01T12:00:00Z"),
                "Retained original",
            ),
            0,
            CollaborationMessageState.Queued,
        )

    fun inbox(store: ReplicaStore = base.database.replicas) =
        EncryptedCollaborationSpaceInboxRepository(store, base.cipher, base.keys)

    fun progress() =
        EncryptedCollaborationSpaceInboxRepairProgressRepository(
            base.database.replicas,
            base.cipher,
            base.keys,
        )

    suspend fun proposal(
        original: CollaborationSpaceMessageRecord = record
    ): CollaborationSpaceInboxPublication {
        val indexed = success(inbox().conversation(access, base.conversation))
        val anchors =
            success(
                inbox()
                    .activities(
                        access,
                        base.conversation,
                        listOf(CollaborationSpaceInboxSource.GroupOriginal(original.intent.id)),
                    )
            )
        return success(
            collaborationSpaceInboxSeedPublication(access, metadata, original, indexed, anchors)
        )
    }

    suspend fun request(index: Int = 1): CollaborationSpaceInboxRepairPublication =
        request(success(progress().current(access)), index)

    fun request(
        expected: CollaborationSpaceInboxRepairProgress?,
        index: Int = 1,
    ): CollaborationSpaceInboxRepairPublication {
        val next =
            success(
                collaborationSpaceInboxRepairProgress(
                    expected,
                    CollaborationSpaceInboxRepairProgressChange.Request(historyId(610 + index)),
                    historyId(710 + index),
                )
            )
        return CollaborationSpaceInboxRepairPublication(expected, next)
    }

    suspend fun seed() {
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxSeedResult.STORED),
            inbox().seed(access, proposal(), request(40)),
        )
    }

    suspend fun snapshot() =
        base.database.replicas
            .observe(spaceHistoryScope(access), spaceInboxPartition(access), limit = 200)
            .first()

    suspend fun references() =
        assertIs<CollaborationSpaceInboxRepairPage.Items>(
                success(
                    EncryptedCollaborationSpaceInboxRepairRepository(
                            base.database.replicas,
                            base.cipher,
                            base.keys,
                        )
                        .page(access)
                )
            )
            .references
}

private fun <T> success(result: AppResult<T>): T = assertIs<AppResult.Success<T>>(result).value
