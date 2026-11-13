package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceComposerIndexStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun theOriginalEditorPendingPreviewAndWakeCommitTogether() = withDatabase { f ->
        val value = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.write(value, null, f.proposal(value)),
        )
        assertEquals(AppResult.Success(value), f.outbox().get(f.local(), value.id))
        assertEquals(
            AppResult.Success(value.nextDraft),
            f.drafts().get(f.local(), value.original.conversationId),
        )
        val current = f.indexed(value.original.conversationId)
        assertEquals(value.nextDraft, current.draft?.value)
        assertNull(current.entry?.draft)
        assertEquals(value.original.text, current.entry?.newest?.preview?.text)
        assertEquals(CollaborationMessageDelivery.PENDING, current.entry?.newest?.preview?.delivery)
        assertTrue(
            assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)).pending
        )
    }

    @Test
    fun anIndependentIndexWritePreventsEveryPartOfTheSourceHandoff() = withDatabase { f ->
        val value = f.submission()
        val ready = f.proposal(value)
        assertEquals(
            AppResult.Success(true),
            f.inbox()
                .publish(
                    f.local(),
                    CollaborationSpaceInboxPublication(
                        null,
                        0,
                        collaborationSpaceInboxEntry(historyId(7701), 0, null, null),
                    ),
                ),
        )
        val before = f.indexSnapshot()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
            f.write(value, null, ready),
        )
        assertNull(f.sourceSnapshot().checkpoint)
        assertEquals(before, f.indexSnapshot())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun anIndependentEditorWriteCannotBeOverwrittenByAnIndexProposal() = withDatabase { f ->
        val value = f.submission()
        f.edit(null, value.original)
        val ready = f.proposal(value)
        val newer = value.original.copy(revision = historyId(7702), text = "Newer source")
        f.writeSourceEditor(newer)
        val source = f.sourceSnapshot()
        val index = f.indexSnapshot()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
            f.write(value, value.original, ready),
        )
        assertEquals(source, f.sourceSnapshot())
        assertEquals(index, f.indexSnapshot())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun aLostCommittedReplyAndLaterTypingReplayWithoutRewritingOrReplacingWake() =
        withDatabase { f ->
            val value = f.submission()
            val ready = f.proposal(value)
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertEquals(2, mutations.size)
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        throw CancellationException("Lost joint committed response")
                    }
                }
            assertFailsWith<CancellationException> { f.write(value, null, ready, store) }
            f.reopen()
            val newer = value.nextDraft.copy(revision = historyId(7703), text = "Keep newer typing")
            f.edit(value.nextDraft, newer)
            val source = f.sourceSnapshot()
            val index = f.indexSnapshot()
            val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.write(value, null, f.proposal(value)),
            )
            assertEquals(source, f.sourceSnapshot())
            assertEquals(index, f.indexSnapshot())
            assertEquals(newer, f.indexed(value.original.conversationId).entry?.draft?.value)
            assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        }

    @Test
    fun twoConversationsHaveOneCoherentWinnerAndTheOtherCanRetry() = withDatabase { f ->
        val values = listOf(f.submission(), f.submission(2, historyId(7704)))
        val ready = values.map { f.proposal(it) }
        val arrived = Channel<Unit>(2)
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    arrived.send(Unit)
                    release.await()
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        try {
            coroutineScope {
                val jobs =
                    values.indices.map { i -> async { f.write(values[i], null, ready[i], store) } }
                withTimeout(5_000) {
                    repeat(2) {
                        select<Unit> {
                            arrived.onReceive {}
                            jobs.forEach { job ->
                                job.onAwait {
                                    error("Writer returned before atomic contention: $it")
                                }
                            }
                        }
                    }
                }
                release.complete(Unit)
                val outcomes = jobs.awaitAll()
                assertEquals(
                    1,
                    outcomes.count {
                        it == AppResult.Success(CollaborationComposerHandoffResult.STORED)
                    },
                )
                assertEquals(
                    1,
                    outcomes.count {
                        it == AppResult.Success(CollaborationComposerHandoffResult.CHANGED)
                    },
                )
                val loser =
                    values[
                        outcomes.indexOf(
                            AppResult.Success(CollaborationComposerHandoffResult.CHANGED)
                        )]
                assertEquals(AppResult.Success(null), f.outbox().get(f.local(), loser.id))
                assertNull(f.indexed(loser.original.conversationId).entry)
                assertEquals(
                    AppResult.Success(CollaborationComposerHandoffResult.STORED),
                    f.write(loser, null, f.proposal(loser)),
                )
                for (value in values) assertEquals(
                    value.original.text,
                    f.indexed(value.original.conversationId).entry?.newest?.preview?.text,
                )
            }
        } finally {
            release.complete(Unit)
            arrived.close()
        }
    }

    @Test
    fun confirmingAnEarlierOriginalKeepsTheNewestPendingPreviewInPlace() = withDatabase { f ->
        val first = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.write(first, null, f.proposal(first)),
        )
        val next = f.submission(2)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.write(next, first.nextDraft, f.proposal(next)),
        )
        val current = f.indexed(f.conversation)
        val source = CollaborationSpaceInboxSource.GroupOriginal(first.id)
        val old =
            success(f.inbox().activities(f.local(), f.conversation, listOf(source))).values.single()
        val sent =
            old.copy(preview = old.preview!!.copy(delivery = CollaborationMessageDelivery.SENT))
        // Simulate independently admitted confirmation; source handoff must retain its anchor.
        assertEquals(
            AppResult.Success(true),
            f.inbox()
                .publish(
                    f.local(),
                    CollaborationSpaceInboxPublication(
                        current.version,
                        current.version!!.lastOrdinal,
                        current.entry!!,
                        activities = listOf(sent),
                    ),
                ),
        )
        val newer = f.indexed(f.conversation).entry!!
        assertEquals(current.entry, newer)
        assertEquals(CollaborationSpaceInboxSource.GroupOriginal(next.id), newer.newest?.source)
        assertEquals(CollaborationMessageDelivery.PENDING, newer.newest?.preview?.delivery)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
            f.write(first, null, f.proposal(first)),
        )
        assertEquals(
            sent,
            success(f.inbox().activities(f.local(), f.conversation, listOf(source))).values.single(),
        )
    }

    @Test
    fun accountReplacementAtCommitCannotWriteIntoEitherOldPartition() = withDatabase { f ->
        val value = f.submission()
        val ready = f.proposal(value)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(
                        f.access.copy(sessionId = "other-login", accountId = historyId(7705))
                    )
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.write(value, null, ready, store)).failure.kind,
        )
        f.activate()
        assertNull(f.sourceSnapshot().checkpoint)
        assertNull(f.indexSnapshot().checkpoint)
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun cancellationBeforeTheTransactionKeepsBothSourcesAndWakeAbsent() = withDatabase { f ->
        val value = f.submission()
        val ready = f.proposal(value)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    throw CancellationException("Before joint commit")
                }
            }
        assertFailsWith<CancellationException> { f.write(value, null, ready, store) }
        assertNull(f.sourceSnapshot().checkpoint)
        assertNull(f.indexSnapshot().checkpoint)
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun aLegacyOriginalRepairsItsMissingIndexWithoutReplacingOriginalBytesOrWake() =
        withDatabase { f ->
            val value = f.submission()
            val editor =
                value.nextDraft.copy(revision = historyId(7706), text = "Legacy newer text")
            val coordinates = CollaborationComposerCoordinates.Space(f.local())
            CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use(create = true) {
                key,
                _ ->
                val codec = CollaborationDraftCodec(coordinates, f.cipher, key)
                val originals = CollaborationComposerSubmissionCodec(coordinates, f.cipher, key)
                val generation = historyId(7707)
                assertTrue(
                    f.database.replicas.replace(
                        coordinates.scope,
                        coordinates.partition,
                        null,
                        ReplicaSnapshot(
                            generation,
                            codec.checkpoint(generation, 0),
                            listOf(
                                codec.encode(generation, 0, editor),
                                originals.encode(generation, 0, value),
                            ),
                        ),
                        WorkWakeRequest(
                            CollaborationSpaceRecoveryWake.WORK_KEY,
                            "original-wake",
                            32,
                        ),
                    )
                )
            }
            val before = f.sourceSnapshot().documents.associateBy { it.id }
            val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.write(value, null, f.proposal(value)),
            )
            assertEquals(before, f.sourceSnapshot().documents.associateBy { it.id })
            assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
            assertEquals(editor, f.indexed(f.conversation).entry?.draft?.value)
            assertEquals(
                value.original.text,
                f.indexed(f.conversation).entry?.newest?.preview?.text,
            )
        }

    private fun CollaborationHistoryFixture.local() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            access.companyId,
        )

    private fun CollaborationHistoryFixture.outbox(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationComposerOutboxRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.drafts() =
        EncryptedCollaborationDraftRepository(database.replicas, cipher, keys)

    private fun CollaborationHistoryFixture.inbox() =
        EncryptedCollaborationSpaceInboxRepository(database.replicas, cipher, keys)

    private fun CollaborationHistoryFixture.submission(
        index: Int = 1,
        conversationId: String = conversation,
    ) =
        CollaborationComposerSubmission(
            CollaborationDraft(
                conversationId,
                historyId(7800 + index * 2),
                "Original $index",
                Instant.parse("2026-11-01T12:00:00Z"),
            ),
            historyId(7801 + index * 2),
            Instant.parse("2026-11-01T12:00:00Z"),
        )

    private fun CollaborationHistoryFixture.metadata(conversationId: String) =
        CollaborationSpaceConversationMetadata(
            local().spaceId,
            conversationId,
            access.accountId,
            historyId(9003),
            access.accountId,
            Instant.fromEpochMilliseconds(0),
            0,
        )

    private suspend fun CollaborationHistoryFixture.indexed(conversationId: String) =
        success(inbox().conversation(local(), conversationId))

    private suspend fun CollaborationHistoryFixture.proposal(
        value: CollaborationComposerSubmission
    ): CollaborationSpaceInboxPublication {
        val current = indexed(value.original.conversationId)
        val anchors =
            success(
                inbox()
                    .activities(
                        local(),
                        value.original.conversationId,
                        listOf(CollaborationSpaceInboxSource.GroupOriginal(value.id)),
                    )
            )
        val retained = success(outbox().get(local(), value.id)) != null
        val editor =
            if (retained)
                assertNotNull(success(drafts().get(local(), value.original.conversationId)))
            else value.nextDraft
        return success(
            collaborationSpaceInboxComposerPublication(
                local(),
                metadata(value.original.conversationId),
                current,
                anchors,
                value,
                editor,
                retained,
            )
        )
    }

    private suspend fun CollaborationHistoryFixture.write(
        value: CollaborationComposerSubmission,
        expected: CollaborationDraft?,
        proposal: CollaborationSpaceInboxPublication,
        store: ReplicaStore = database.replicas,
    ) = outbox(store).handoff(local(), value, expected, proposal)

    private suspend fun CollaborationHistoryFixture.edit(
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ) {
        val proposal =
            success(
                collaborationSpaceInboxDraftPublication(
                    metadata(replacement.conversationId),
                    indexed(replacement.conversationId),
                    replacement,
                )
            )
        assertEquals(
            AppResult.Success(true),
            drafts().replace(local(), expected, replacement, proposal),
        )
    }

    private suspend fun CollaborationHistoryFixture.sourceSnapshot(): ReplicaPage {
        val coordinates = CollaborationComposerCoordinates.Space(local())
        return database.replicas
            .observe(coordinates.scope, coordinates.partition, limit = 200)
            .first()
    }

    private suspend fun CollaborationHistoryFixture.indexSnapshot() =
        database.replicas
            .observe(spaceHistoryScope(local()), spaceInboxPartition(local()), limit = 200)
            .first()

    private suspend fun CollaborationHistoryFixture.writeSourceEditor(value: CollaborationDraft) {
        val coordinates = CollaborationComposerCoordinates.Space(local())
        val raw = assertNotNull(sourceSnapshot().checkpoint)
        CollaborationHistoryKeys(database.replicas, cipher, keys).use(create = false) { key, _ ->
            val codec = CollaborationDraftCodec(coordinates, cipher, key)
            val next = raw.revision + 1
            assertTrue(
                database.replicas.commit(
                    raw.handle,
                    raw.revision,
                    codec.checkpoint(raw.handle.generation, next),
                    listOf(codec.encode(raw.handle.generation, next, value)),
                    true,
                )
            )
        }
    }

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value

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
