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

abstract class CollaborationSpaceInboxRepairStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun aSendRetainsItsExactRepairOriginalWithSourceIndexAndWakeAcrossRestart() =
        withDatabase { f ->
            val original = f.send()
            val before = f.indexSnapshot()
            val wake =
                assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
            f.reopen()
            val page = f.repairs()
            assertEquals(listOf(f.intent(original)), page.references.map { it.intent })
            assertEquals(0L, page.references.single().insertedAtRevision)
            assertNull(page.next)
            assertEquals(before, f.indexSnapshot())
            assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
            assertEquals(AppResult.Success(original), f.outbox().get(f.local(), original.id))
        }

    @Test
    fun completionPublishesItsPreviewAndRetiresOnlyThatReferenceAtomically() = withDatabase { f ->
        val first = f.send()
        val second = f.send(2)
        val references = f.repairs().references
        assertEquals(listOf(f.intent(first), f.intent(second)), references.map { it.intent })
        f.complete(references.first())
        assertEquals(listOf(f.intent(second)), f.repairs().references.map { it.intent })
        val current = f.indexed()
        val newest = assertNotNull(current.entry?.newest)
        assertEquals(second.id, (newest.source as CollaborationSpaceInboxSource.GroupOriginal).id)
        assertEquals(CollaborationMessageDelivery.PENDING, newest.preview?.delivery)
        val firstAnchor =
            success(
                    f.inbox()
                        .activities(
                            f.local(),
                            f.conversation,
                            listOf(CollaborationSpaceInboxSource.GroupOriginal(first.id)),
                        )
                )
                .values
                .single()
        assertEquals(CollaborationMessageDelivery.SENT, firstAnchor.preview!!.delivery)
    }

    @Test
    fun aLostCompletionReplySurvivesRestartAndOriginalReplayCannotReviveItsReference() =
        withDatabase { f ->
            val original = f.send()
            val reference = f.repairs().references.single()
            val interrupted =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        throw CancellationException("Lost completion response")
                    }
                }
            assertFailsWith<CancellationException> { f.complete(reference, interrupted) }
            f.reopen()
            assertTrue(f.repairs().references.isEmpty())
            val before = f.indexSnapshot()
            f.complete(reference)
            assertEquals(before, f.indexSnapshot())
            val replay =
                success(
                    collaborationSpaceInboxComposerPublication(
                        f.local(),
                        f.metadata(),
                        f.indexed(),
                        success(
                            f.inbox()
                                .activities(
                                    f.local(),
                                    f.conversation,
                                    listOf(CollaborationSpaceInboxSource.GroupOriginal(original.id)),
                                )
                        ),
                        original,
                        original.nextDraft,
                        true,
                    )
                )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.outbox().handoff(f.local(), original, null, replay),
            )
            assertEquals(before, f.indexSnapshot())
            assertTrue(f.repairs().references.isEmpty())
        }

    @Test
    fun aCapturedPassSurvivesCompletionAndDefersNewReferencesToTheNextPass() = withDatabase { f ->
        val first = f.send()
        val second = f.send(2)
        val third = f.send(3)
        val page = f.repairs(limit = 1)
        assertEquals(f.intent(first), page.references.single().intent)
        f.complete(page.references.single())
        val fourth = f.send(4)
        val middle = f.repairs(assertNotNull(page.next), 1)
        assertEquals(f.intent(second), middle.references.single().intent)
        val end = f.repairs(assertNotNull(middle.next), 1)
        assertEquals(f.intent(third), end.references.single().intent)
        assertNull(end.next)
        assertEquals(
            listOf(f.intent(second), f.intent(third), f.intent(fourth)),
            f.repairs().references.map { it.intent },
        )
    }

    @Test
    fun anIndexConflictCannotRetireARepairOrPublishItsAcknowledgement() = withDatabase { f ->
        f.send()
        val ref = f.repairs().references.single()
        val ready = f.completion(ref)
        f.send(2)
        val before = f.indexSnapshot()
        assertEquals(AppResult.Success(false), f.inbox().publish(f.local(), ready))
        assertEquals(before, f.indexSnapshot())
        assertEquals(2, f.repairs().references.size)
        val anchor =
            success(
                    f.inbox()
                        .activities(
                            f.local(),
                            f.conversation,
                            listOf(CollaborationSpaceInboxSource.GroupOriginal(ref.intent.id)),
                        )
                )
                .values
                .single()
        assertEquals(CollaborationMessageDelivery.PENDING, anchor.preview!!.delivery)
    }

    @Test
    fun cancellationBeforeCommitKeepsThePendingPreviewAndRepairTogether() = withDatabase { f ->
        f.send()
        val ref = f.repairs().references.single()
        val before = f.indexSnapshot()
        val interrupted =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    throw CancellationException("Before completion commit")
                }
            }
        assertFailsWith<CancellationException> { f.complete(ref, interrupted) }
        assertEquals(before, f.indexSnapshot())
        assertEquals(listOf(ref), f.repairs().references)
    }

    @Test
    fun anotherOriginalWithTheSameIdCannotCompleteTheRetainedReference() = withDatabase { f ->
        f.send()
        val ref = f.repairs().references.single()
        val changed = ref.copy(intent = ref.intent.copy(text = "Different original"))
        val ready = f.completion(changed)
        val before = f.indexSnapshot()
        assertIs<AppResult.Failed>(f.inbox().publish(f.local(), ready))
        assertEquals(before, f.indexSnapshot())
        assertEquals(listOf(ref), f.repairs().references)
    }

    @Test
    fun completionDropsTheOriginalTextAndRetainsItsStableActivityAnchor() = withDatabase { f ->
        val original = f.send()
        val ref = f.repairs().references.single()
        val before = f.indexed().entry!!.newest!!
        f.complete(ref)
        val raw = f.indexSnapshot()
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { key, index ->
            val codec = CollaborationSpaceInboxCodec(f.local(), f.cipher, key, index)
            val id =
                codec.id(
                    "repair",
                    f.conversation,
                    CollaborationSpaceInboxSource.GroupOriginal(original.id),
                )
            val marker =
                assertNotNull(
                    codec
                        .decode(assertNotNull(raw.checkpoint), raw.documents.single { it.id == id })
                        .repair
                )
            assertNull(marker.intent)
            assertEquals(ref.insertedAtRevision, marker.insertedAtRevision)
            assertEquals(64, marker.digest.length)
        }
        val after = f.indexed().entry!!.newest!!
        assertEquals(before.position, after.position)
        assertEquals(before.preview!!.timestamp, after.preview!!.timestamp)
    }

    @Test
    fun eachReadUsesOneBoundedRepairSubsetAndRejectsOversizedLimits() = withDatabase { f ->
        repeat(4) { f.send(it + 1) }
        val capturedQueries = mutableListOf<List<ReplicaQuery>>()
        val bounded =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    capturedQueries += queries
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }

                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("Whole index scan")
            }
        val repo = EncryptedCollaborationSpaceInboxRepairRepository(bounded, f.cipher, f.keys)
        val page =
            assertIs<CollaborationSpaceInboxRepairPage.Items>(
                success(repo.page(f.local(), limit = 2))
            )
        assertEquals(2, page.references.size)
        assertNotNull(page.next)
        assertEquals(listOf(listOf(ReplicaQuery(SPACE_INBOX_REPAIRS, limit = 3))), capturedQueries)
        assertIs<AppResult.Failed>(repo.page(f.local(), limit = 0))
        assertIs<AppResult.Failed>(repo.page(f.local(), limit = 33))
        assertEquals(1, capturedQueries.size)
    }

    @Test
    fun removingTheGenerationRestartsAnExistingCursorWithoutExposingAnotherAccount() =
        withDatabase { f ->
            f.send()
            f.send(2)
            val page = f.repairs(limit = 1)
            val cursor = assertNotNull(page.next)
            assertTrue(
                f.database.replicas.clear(
                    spaceHistoryScope(f.local()),
                    spaceInboxPartition(f.local()),
                )
            )
            val repo =
                EncryptedCollaborationSpaceInboxRepairRepository(
                    f.database.replicas,
                    f.cipher,
                    f.keys,
                )
            assertEquals(
                AppResult.Success(CollaborationSpaceInboxRepairPage.Restart),
                repo.page(f.local(), cursor),
            )
            assertTrue(f.repairs().references.isEmpty())
            f.activate(f.access.copy(accountId = historyId(9500), sessionId = "other-login"))
            assertTrue(f.repairs().references.isEmpty())
            assertEquals(
                AppResult.Success(CollaborationSpaceInboxRepairPage.Restart),
                repo.page(f.local(), cursor),
            )
        }

    @Test
    fun sessionRenewalKeepsOriginalReferencesAndReplacementAccountCannotReadThem() =
        withDatabase { f ->
            val original = f.send()
            f.send(2)
            val first = f.repairs(limit = 1)
            val cursor = assertNotNull(first.next)
            val otherGroup = f.local().copy(spaceId = historyId(9501))
            assertEquals(
                AppResult.Success(true),
                f.inbox()
                    .publish(
                        otherGroup,
                        CollaborationSpaceInboxPublication(
                            null,
                            0,
                            collaborationSpaceInboxEntry(f.conversation, 0, null, null),
                        ),
                    ),
            )
            val isolated =
                EncryptedCollaborationSpaceInboxRepairRepository(
                    f.database.replicas,
                    f.cipher,
                    f.keys,
                )
            assertIs<AppResult.Failed>(isolated.page(otherGroup, cursor))
            assertTrue(
                assertIs<CollaborationSpaceInboxRepairPage.Items>(
                        success(isolated.page(otherGroup))
                    )
                    .references
                    .isEmpty()
            )
            val renewed = f.access.copy(sessionId = "renewed-login")
            f.reopen(renewed)
            val repo =
                EncryptedCollaborationSpaceInboxRepairRepository(
                    f.database.replicas,
                    f.cipher,
                    f.keys,
                )
            val owned = f.local().copy(sessionId = renewed.sessionId)
            val restored =
                assertIs<CollaborationSpaceInboxRepairPage.Items>(success(repo.page(owned)))
            assertEquals(f.intent(original), restored.references.first().intent)
            assertEquals(2, restored.references.size)
            assertTrue(f.repairs().references.isEmpty())
            f.activate(f.access.copy(accountId = historyId(9500), sessionId = "other-login"))
            val unavailable =
                assertIs<CollaborationSpaceInboxRepairPage.Items>(success(repo.page(owned)))
            assertTrue(unavailable.references.isEmpty())
            assertEquals(
                AppResult.Success(CollaborationSpaceInboxRepairPage.Restart),
                repo.page(owned, cursor),
            )
            f.activate(renewed)
            val remaining =
                assertIs<CollaborationSpaceInboxRepairPage.Items>(success(repo.page(owned, cursor)))
            assertEquals(1, remaining.references.size)
            assertNull(remaining.next)
        }

    @Test
    fun hidingNewestAndCompletingItsReferenceRevealsEarlierPendingAfterRestart() =
        withDatabase { f ->
            val first = f.send()
            val second = f.send(2)
            val reference = f.repairs().references.last()
            val current = f.indexed()
            val earlier =
                current.candidates.single {
                    it.source == CollaborationSpaceInboxSource.GroupOriginal(first.id)
                }
            val newest =
                current.candidates.single {
                    it.source == CollaborationSpaceInboxSource.GroupOriginal(second.id)
                }
            val ready =
                CollaborationSpaceInboxPublication(
                    current.version,
                    current.version!!.lastOrdinal,
                    collaborationSpaceInboxEntry(
                        f.conversation,
                        current.entry!!.createdAt,
                        current.draft,
                        earlier,
                    ),
                    activities = listOf(newest.copy(preview = null)),
                    repair = CollaborationSpaceInboxRepairChange.Complete(reference),
                )
            assertEquals(AppResult.Success(true), f.inbox().publish(f.local(), ready))
            f.reopen()
            assertEquals(earlier, f.indexed().entry?.newest)
            assertEquals(listOf(f.intent(first)), f.repairs().references.map { it.intent })
            val hidden =
                success(f.inbox().activities(f.local(), f.conversation, listOf(newest.source)))
                    .values
                    .single()
            assertNull(hidden.preview)
            assertEquals(newest.position, hidden.position)
        }

    @Test
    fun anExistingCommandCanRetainAndCompleteAReferenceWithoutAnyEditorEnvelope() =
        withDatabase { f ->
            val intent =
                CollaborationSpaceMessageIntent(
                    historyId(8701),
                    f.local().messageOwner(),
                    f.conversation,
                    Instant.parse("2026-11-01T12:00:00Z"),
                    "Retained forwarded command",
                    forwarded = true,
                )
            val activity =
                CollaborationSpaceInboxActivity(
                    CollaborationSpaceInboxSource.GroupOriginal(intent.id),
                    CollaborationSpaceInboxPosition(intent.createdAt.toEpochMilliseconds(), 1),
                    CollaborationSpaceInboxPreview(
                        CollaborationSpaceInboxContent.TEXT,
                        intent.text,
                        intent.createdAt.toEpochMilliseconds(),
                        CollaborationMessageDelivery.PENDING,
                    ),
                )
            val ready =
                CollaborationSpaceInboxPublication(
                    null,
                    1,
                    collaborationSpaceInboxEntry(f.conversation, 0, null, activity),
                    activities = listOf(activity),
                    repair = CollaborationSpaceInboxRepairChange.Retain(intent),
                )
            val foreign =
                ready.copy(
                    repair =
                        CollaborationSpaceInboxRepairChange.Retain(
                            intent.copy(owner = intent.owner.copy(accountId = historyId(8702)))
                        )
                )
            assertIs<AppResult.Failed>(f.inbox().publish(f.local(), foreign))
            assertNull(f.indexSnapshot().checkpoint)
            assertEquals(AppResult.Success(true), f.inbox().publish(f.local(), ready))
            f.reopen()
            val reference = f.repairs().references.single()
            assertEquals(intent, reference.intent)
            assertEquals(AppResult.Success(null), f.outbox().get(f.local(), intent.id))
            assertNull(f.indexed().draft)
            f.complete(reference)
            assertTrue(f.repairs().references.isEmpty())
            assertEquals(
                CollaborationMessageDelivery.SENT,
                f.indexed().entry?.newest?.preview?.delivery,
            )
        }

    private fun CollaborationHistoryFixture.intent(value: CollaborationComposerSubmission) =
        collaborationSpaceComposerIntent(local(), value)

    private fun CollaborationHistoryFixture.local() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            access.companyId,
        )

    private fun CollaborationHistoryFixture.inbox(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceInboxRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.outbox() =
        EncryptedCollaborationComposerOutboxRepository(database.replicas, cipher, keys)

    private fun CollaborationHistoryFixture.metadata() =
        CollaborationSpaceConversationMetadata(
            local().spaceId,
            conversation,
            access.accountId,
            historyId(9003),
            access.accountId,
            Instant.fromEpochMilliseconds(0),
            0,
        )

    private suspend fun CollaborationHistoryFixture.indexed() =
        success(inbox().conversation(local(), conversation))

    private suspend fun CollaborationHistoryFixture.repairs(
        after: String? = null,
        limit: Int = 16,
    ) =
        assertIs<CollaborationSpaceInboxRepairPage.Items>(
            success(
                EncryptedCollaborationSpaceInboxRepairRepository(database.replicas, cipher, keys)
                    .page(local(), after, limit)
            )
        )

    private suspend fun CollaborationHistoryFixture.indexSnapshot() =
        database.replicas
            .observe(spaceHistoryScope(local()), spaceInboxPartition(local()), limit = 200)
            .first()

    private suspend fun CollaborationHistoryFixture.send(
        index: Int = 1
    ): CollaborationComposerSubmission {
        val original =
            CollaborationComposerSubmission(
                CollaborationDraft(
                    conversation,
                    historyId(8400 + index * 2),
                    "Original $index",
                    Instant.parse("2026-11-01T12:00:00Z"),
                ),
                historyId(8401 + index * 2),
                Instant.parse("2026-11-01T12:00:00Z"),
            )
        val current = indexed()
        val editor =
            success(
                EncryptedCollaborationDraftRepository(database.replicas, cipher, keys)
                    .get(local(), conversation)
            )
        val proposal =
            success(
                collaborationSpaceInboxComposerPublication(
                    local(),
                    metadata(),
                    current,
                    success(
                        inbox()
                            .activities(
                                local(),
                                conversation,
                                listOf(CollaborationSpaceInboxSource.GroupOriginal(original.id)),
                            )
                    ),
                    original,
                    original.nextDraft,
                    false,
                )
            )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            outbox().handoff(local(), original, editor, proposal),
        )
        return original
    }

    private suspend fun CollaborationHistoryFixture.completion(
        reference: CollaborationSpaceInboxRepairReference
    ): CollaborationSpaceInboxPublication {
        val current = indexed()
        val source = CollaborationSpaceInboxSource.GroupOriginal(reference.intent.id)
        val anchor =
            success(inbox().activities(local(), conversation, listOf(source))).values.single()
        val changed =
            anchor.copy(
                preview = anchor.preview?.copy(delivery = CollaborationMessageDelivery.SENT)
            )
        val newest =
            (current.candidates.filter { it.source != source } +
                    listOfNotNull(changed.takeIf { it.preview != null }))
                .maxWithOrNull { a, b ->
                    compareCollaborationSpaceInboxPositions(a.position, b.position)
                }
        return CollaborationSpaceInboxPublication(
            current.version,
            current.version!!.lastOrdinal,
            collaborationSpaceInboxEntry(
                conversation,
                current.entry!!.createdAt,
                current.draft,
                newest,
            ),
            activities = listOf(changed),
            repair = CollaborationSpaceInboxRepairChange.Complete(reference),
        )
    }

    private suspend fun CollaborationHistoryFixture.complete(
        reference: CollaborationSpaceInboxRepairReference,
        store: ReplicaStore = database.replicas,
    ) {
        assertEquals(AppResult.Success(true), inbox(store).publish(local(), completion(reference)))
    }

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val fixture = CollaborationHistoryFixture(databaseOwner())
            try {
                fixture.activate()
                block(fixture)
            } finally {
                fixture.close()
            }
        }
    }
}
