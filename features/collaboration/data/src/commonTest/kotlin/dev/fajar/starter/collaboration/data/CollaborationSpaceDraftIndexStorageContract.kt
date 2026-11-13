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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceDraftIndexStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun editingAndClearingPublishSourceAndIndexTogether() = withDatabase { f ->
        val first = f.editor(1)
        assertEquals(AppResult.Success(true), f.write(null, first, f.proposal(first)))
        f.assertRetained(first)
        val cleared = f.editor(2).copy(text = "")
        assertEquals(AppResult.Success(true), f.write(first, cleared, f.proposal(cleared)))
        f.assertRetained(cleared)
        assertNull(f.indexed().entry?.draft)
        assertEquals(2L, f.indexed().version?.lastOrdinal)
    }

    @Test
    fun lostCommittedResponseReopensBothAndExactReplayDoesNotRewriteEither() = withDatabase { f ->
        val first = f.editor(1)
        val proposal = f.proposal(first)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertEquals(2, mutations.size)
                    assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                    throw CancellationException("response lost after joint commit")
                }
            }
        assertFailsWith<CancellationException> { f.write(null, first, proposal, store) }
        f.reopen()
        f.assertRetained(first)
        val beforeSource = f.sourceSnapshot()
        val beforeIndex = f.indexSnapshot()
        assertEquals(AppResult.Success(true), f.write(null, first, f.proposal(first)))
        assertEquals(beforeSource, f.sourceSnapshot())
        assertEquals(beforeIndex, f.indexSnapshot())
    }

    @Test
    fun anIndependentIndexConflictLeavesTheSourceUntouched() = withDatabase { f ->
        val first = f.editor(1)
        val stale = f.proposal(first)
        val other = collaborationSpaceInboxEntry(historyId(8801), 0, null, null)
        assertEquals(
            AppResult.Success(true),
            f.indexRepository()
                .publish(f.local(), CollaborationSpaceInboxPublication(null, 0, other)),
        )
        val retained = f.indexSnapshot()
        assertEquals(AppResult.Success(false), f.write(null, first, stale))
        assertEquals(AppResult.Success(null), f.draftRepository().get(f.local(), f.conversation))
        assertEquals(retained, f.indexSnapshot())
    }

    @Test
    fun anIndependentSourceConflictCannotCommitAnOtherwiseCurrentIndexProposal() =
        withDatabase { f ->
            val first = f.editor(1)
            assertEquals(AppResult.Success(true), f.write(null, first, f.proposal(first)))
            val late = f.editor(2)
            val ready = f.proposal(late)
            val newer = f.editor(3)
            f.writeSourceOnly(newer)
            val retained = f.indexSnapshot()
            assertEquals(AppResult.Success(false), f.write(first, late, ready))
            assertEquals(
                AppResult.Success(newer),
                f.draftRepository().get(f.local(), f.conversation),
            )
            assertEquals(retained, f.indexSnapshot())
        }

    @Test
    fun draftPublicationCannotReplaceAnExistingMessagePreview() = withDatabase { f ->
        val first = f.editor(1)
        val candidate =
            CollaborationSpaceInboxActivity(
                CollaborationSpaceInboxSource.GroupOriginal(historyId(8802)),
                CollaborationSpaceInboxPosition(1000, 1),
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.TEXT,
                    "Original message",
                    1000,
                    CollaborationMessageDelivery.PENDING,
                ),
            )
        val entry = collaborationSpaceInboxEntry(f.conversation, 0, null, candidate)
        assertEquals(
            AppResult.Success(true),
            f.indexRepository()
                .publish(
                    f.local(),
                    CollaborationSpaceInboxPublication(
                        null,
                        1,
                        entry,
                        activities = listOf(candidate),
                    ),
                ),
        )
        val ready = f.proposal(first)
        val retained = f.indexSnapshot()
        val changed = candidate.copy(preview = candidate.preview!!.copy(text = "Injected edit"))
        assertIs<AppResult.Failed>(
            f.write(null, first, ready.copy(entry = ready.entry.copy(newest = changed)))
        )
        assertEquals(AppResult.Success(null), f.draftRepository().get(f.local(), f.conversation))
        assertEquals(retained, f.indexSnapshot())
        assertEquals(AppResult.Success(true), f.write(null, first, ready))
        assertEquals(candidate, f.indexed().entry?.newest)
    }

    @Test
    fun anExistingLegacySourceRepairsItsIndexWithoutReencryptingTheOriginalBody() =
        withDatabase { f ->
            val first = f.editor(1)
            f.writeSourceOnly(first)
            val before = f.sourceSnapshot()
            assertNull(f.indexed().version)
            assertEquals(AppResult.Success(true), f.write(null, first, f.proposal(first)))
            f.assertRetained(first)
            assertEquals(before.documents, f.sourceSnapshot().documents)
            assertEquals(before.checkpoint!!.revision + 1, f.sourceSnapshot().checkpoint?.revision)
        }

    @Test
    fun cancellationBeforeTheJointTransactionLeavesBothTargetsEmpty() = withDatabase { f ->
        val first = f.editor(1)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean = throw CancellationException("disposed before commit")
            }
        assertFailsWith<CancellationException> { f.write(null, first, f.proposal(first), store) }
        assertEquals(AppResult.Success(null), f.draftRepository().get(f.local(), f.conversation))
        assertNull(f.indexed().entry)
        assertNull(f.sourceSnapshot().checkpoint)
        assertNull(f.indexSnapshot().checkpoint)
    }

    @Test
    fun accountReplacementAtTheCommitFenceCannotRedirectEitherPartition() = withDatabase { f ->
        val first = f.editor(1)
        val replacement = f.access.copy(sessionId = "other-login", accountId = historyId(8803))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(replacement)
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        assertEquals(AppResult.Success(false), f.write(null, first, f.proposal(first), store))
        assertEquals(
            AppResult.Success(null),
            f.draftRepository().get(f.local(replacement), f.conversation),
        )
        assertNull(
            f.result(f.indexRepository().conversation(f.local(replacement), f.conversation)).entry
        )
        f.activate()
        assertNull(f.sourceSnapshot().checkpoint)
        assertNull(f.indexed().entry)
    }

    @Test
    fun simultaneousDifferentEditorsHaveOneCoherentWinnerThenRetryTheSameLoser() =
        withDatabase { f ->
            val first = f.editor(1)
            val second = f.editor(2).copy(conversationId = historyId(8804))
            val firstPlan = f.proposal(first)
            val secondPlan = f.proposal(second)
            val arrivals = Channel<Unit>(2)
            val release = CompletableDeferred<Unit>()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        arrivals.send(Unit)
                        release.await()
                        return f.database.replicas.mutate(scope, mutations, wake)
                    }
                }
            val results =
                try {
                    coroutineScope {
                        val a = async { f.write(null, first, firstPlan, store) }
                        val b = async { f.write(null, second, secondPlan, store) }
                        withContext(Dispatchers.Default) {
                            withTimeout(5000) {
                                repeat(2) {
                                    select<Unit> {
                                        arrivals.onReceive {}
                                        a.onAwait {
                                            fail("First writer returned before both arrivals: $it")
                                        }
                                        b.onAwait {
                                            fail("Second writer returned before both arrivals: $it")
                                        }
                                    }
                                }
                            }
                        }
                        release.complete(Unit)
                        listOf(a.await(), b.await())
                    }
                } finally {
                    arrivals.close()
                }
            assertEquals(setOf(AppResult.Success(true), AppResult.Success(false)), results.toSet())
            val winner = if (results[0] == AppResult.Success(true)) first else second
            val loser = if (winner == first) second else first
            f.assertRetained(winner)
            assertEquals(
                AppResult.Success(null),
                f.draftRepository().get(f.local(), loser.conversationId),
            )
            assertNull(f.indexed(loser.conversationId).entry)
            assertEquals(AppResult.Success(true), f.write(null, loser, f.proposal(loser)))
            f.assertRetained(loser)
            assertEquals(2L, f.indexed(loser.conversationId).draft?.position?.ordinal)
        }

    private fun CollaborationHistoryFixture.local(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(8800),
        )

    private fun CollaborationHistoryFixture.editor(id: Int) =
        CollaborationDraft(
            conversation,
            historyId(8900 + id),
            "Editor $id",
            Instant.parse("2026-11-13T08:00:00Z"),
        )

    private fun CollaborationHistoryFixture.draftRepository(
        store: ReplicaStore = database.replicas
    ) = EncryptedCollaborationDraftRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.indexRepository() =
        EncryptedCollaborationSpaceInboxRepository(database.replicas, cipher, keys)

    private fun <T> CollaborationHistoryFixture.result(value: AppResult<T>): T =
        assertIs<AppResult.Success<T>>(value).value

    private suspend fun CollaborationHistoryFixture.indexed(conversationId: String = conversation) =
        result(indexRepository().conversation(local(), conversationId))

    private suspend fun CollaborationHistoryFixture.proposal(
        value: CollaborationDraft
    ): CollaborationSpaceInboxPublication {
        val current = indexed(value.conversationId)
        val retained = current.draft?.takeIf { it.value == value }
        val last = current.version?.lastOrdinal ?: 0L
        val draft =
            retained
                ?: CollaborationSpaceInboxDraft(
                    value,
                    CollaborationSpaceInboxPosition(value.savedAt.toEpochMilliseconds(), last + 1),
                )
        val entry =
            collaborationSpaceInboxEntry(
                value.conversationId,
                current.entry?.createdAt ?: 0L,
                draft,
                current.candidates.firstOrNull(),
            )
        return CollaborationSpaceInboxPublication(
            current.version,
            if (retained == null) last + 1 else last,
            entry,
            draft,
        )
    }

    private suspend fun CollaborationHistoryFixture.write(
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
        publication: CollaborationSpaceInboxPublication,
        store: ReplicaStore = database.replicas,
    ) = draftRepository(store).replace(local(), expected, replacement, publication)

    private suspend fun CollaborationHistoryFixture.assertRetained(value: CollaborationDraft) {
        assertEquals(AppResult.Success(value), draftRepository().get(local(), value.conversationId))
        val index = indexed(value.conversationId)
        assertEquals(value, index.draft?.value)
        assertEquals(value.takeIf { it.text.isNotBlank() }, index.entry?.draft?.value)
    }

    private suspend fun CollaborationHistoryFixture.sourceSnapshot(): ReplicaPage {
        val coordinates = CollaborationComposerCoordinates.Space(local())
        val page =
            database.replicas.observe(coordinates.scope, coordinates.partition, limit = 200).first()
        page.checkpoint?.let { assertEquals(coordinates.partition, it.handle.partition) }
        return page
    }

    private suspend fun CollaborationHistoryFixture.indexSnapshot(): ReplicaPage {
        val partition = spaceInboxPartition(local())
        val page =
            database.replicas.observe(spaceHistoryScope(local()), partition, limit = 200).first()
        page.checkpoint?.let { assertEquals(partition, it.handle.partition) }
        return page
    }

    /** Models an existing source-only writer while keeping the original byte protocol. */
    private suspend fun CollaborationHistoryFixture.writeSourceOnly(value: CollaborationDraft) {
        val coordinates = CollaborationComposerCoordinates.Space(local())
        val current = sourceSnapshot().checkpoint
        CollaborationHistoryKeys(database.replicas, cipher, keys).use(create = current == null) {
            content,
            _ ->
            val codec = CollaborationDraftCodec(coordinates, cipher, content)
            val generation = current?.handle?.generation ?: historyId(8999)
            val revision = current?.revision?.plus(1) ?: 0L
            val checkpoint = codec.checkpoint(generation, revision)
            val documents = listOf(codec.encode(generation, revision, value))
            assertTrue(
                if (current == null)
                    database.replicas.replace(
                        coordinates.scope,
                        coordinates.partition,
                        null,
                        ReplicaSnapshot(generation, checkpoint, documents),
                    )
                else
                    database.replicas.commit(
                        current.handle,
                        current.revision,
                        checkpoint,
                        documents,
                        true,
                    )
            )
        }
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
