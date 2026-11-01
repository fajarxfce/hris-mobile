package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationDraftRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

abstract class CollaborationDraftStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun draftAndFrozenSubmissionSurviveReopenWithoutPlaintextOrNewKeys() = withDatabase { f ->
        var repository = f.drafts()
        assertEquals(AppResult.Success(null), repository.get(f.access, f.conversation))
        assertNull(f.keys.read())
        val draft = f.draft(text = "  review\nplease 😀  ")
        assertEquals(AppResult.Success(true), repository.replace(f.access, null, draft))
        val key = f.keys.read()
        val raw = f.draftPage()
        assertFalse(raw.toString().contains(draft.text))
        assertTrue(requireNotNull(raw.checkpoint).payload.length <= 32_768)
        f.reopen()
        repository = f.drafts()
        assertEquals(AppResult.Success(draft), repository.get(f.access, f.conversation))
        val frozen = draft.copy(revision = historyId(1002), submissionId = historyId(1003))
        assertEquals(AppResult.Success(true), repository.replace(f.access, draft, frozen))
        val renewed = f.access.copy(sessionId = "renewed-draft-login")
        f.reopen(renewed)
        repository = f.drafts()
        assertEquals(AppResult.Success(frozen), repository.get(renewed, f.conversation))
        assertEquals(AppResult.Success(null), repository.get(f.access, f.conversation))
        assertEquals(key, f.keys.read())
    }

    @Test
    fun exactReplayAndEmptyRevisionFenceLateEditsAndSameRevisionReplacement() = withDatabase { f ->
        val repository = f.drafts()
        val first = f.draft()
        assertEquals(AppResult.Success(true), repository.replace(f.access, null, first))
        assertEquals(AppResult.Success(true), repository.replace(f.access, null, first))
        assertIs<AppResult.Failed>(
            repository.replace(f.access, first, first.copy(text = "changed under same revision"))
        )
        val cleared = first.copy(revision = historyId(1002), text = "")
        assertEquals(AppResult.Success(true), repository.replace(f.access, first, cleared))
        assertEquals(AppResult.Success(false), repository.replace(f.access, null, first))
        assertEquals(
            AppResult.Success(false),
            repository.replace(f.access, first, first.copy(revision = historyId(1004))),
        )
        f.reopen()
        assertEquals(AppResult.Success(cleared), f.drafts().get(f.access, f.conversation))
    }

    @Test
    fun competingInitialWritersCommitOnlyOneDraftAndUseOneProtectedKey() = withDatabase { f ->
        val release = CompletableDeferred<Unit>()
        var arrived = 0
        val barrier = Mutex()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    barrier.withLock { if (++arrived == 2) release.complete(Unit) }
                    release.await()
                    return f.database.replicas.replace(scope, partition, expected, snapshot)
                }
            }
        val one = f.draft()
        val two = one.copy(revision = historyId(1002), text = "Another writer")
        val results = coroutineScope {
            listOf(
                    async { f.drafts(store).replace(f.access, null, one) },
                    async { f.drafts(store).replace(f.access, null, two) },
                )
                .awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        val retained =
            assertIs<AppResult.Success<CollaborationDraft?>>(
                    f.drafts().get(f.access, f.conversation)
                )
                .value
        assertTrue(retained in listOf(one, two))
    }

    @Test
    fun committedWriteWithLostReplyReplaysWithoutReplacingItsRevision() = withDatabase { f ->
        val draft = f.draft(submissionId = historyId(1005))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    assertTrue(f.database.replicas.replace(scope, partition, expected, snapshot))
                    error("Lost local reply")
                }
            }
        assertIs<AppResult.Failed>(f.drafts(store).replace(f.access, null, draft))
        val raw = f.draftPage()
        f.reopen()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        assertEquals(raw, f.draftPage())
    }

    @Test
    fun maximumUtf8DraftUsesABoundedDocumentInsteadOfOverflowingTheCheckpoint() =
        withDatabase { f ->
            val draft = f.draft(text = "é".repeat(16_384))
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
            val raw = f.draftPage()
            assertTrue(raw.documents.single().payload!!.length > 32_768)
            assertTrue(raw.checkpoint!!.payload.length < 200)
            assertEquals(AppResult.Success(draft), f.drafts().get(f.access, f.conversation))
        }

    @Test
    fun invalidTextIdentityOrFrozenEmptyInputNeverCreatesAKeyOrRecord() = withDatabase { f ->
        val good = f.draft()
        for (draft in
            listOf(
                good.copy(text = "x".repeat(16_385)),
                good.copy(text = "\uD800"),
                good.copy(text = "\u0000"),
                good.copy(text = "€".repeat(16_384)),
                good.copy(revision = "invalid"),
                good.copy(savedAt = Instant.fromEpochMilliseconds(-1)),
                good.copy(text = "", submissionId = historyId(1003)),
            )) {
            assertIs<AppResult.Failed>(f.drafts().replace(f.access, null, draft))
        }
        assertNull(f.keys.read())
        assertNull(f.draftPage().checkpoint)
    }

    @Test
    fun substitutedOwnershipGenerationCiphertextAndDocumentMetadataFailClosed() =
        withDatabase { f ->
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, f.draft()))
            val raw = f.draftPage()
            val checkpoint = requireNotNull(raw.checkpoint)
            val doc = raw.documents.single()
            val malformed =
                listOf(
                    raw.copy(checkpoint = checkpoint.copy(revision = 1)),
                    raw.copy(
                        checkpoint =
                            checkpoint.copy(
                                handle = checkpoint.handle.copy(generation = historyId(999))
                            )
                    ),
                    raw.copy(documents = listOf(doc.copy(id = "another"))),
                    raw.copy(documents = listOf(doc.copy(sortKey = "another"))),
                    raw.copy(documents = listOf(doc.copy(version = 1))),
                    raw.copy(documents = listOf(doc.copy(tags = setOf("forged")))),
                    raw.copy(documents = listOf(doc.copy(payload = checkpoint.payload))),
                    raw.copy(documents = listOf(doc, doc)),
                )
            for (page in malformed) {
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observeDocument(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ) = flowOf(page)
                    }
                assertIs<AppResult.Failed>(
                    f.drafts(store).observe(f.access, f.conversation).first()
                )
            }
            val wrong = f.access.copy(companyId = historyId(999))
            val renamed =
                raw.copy(
                    checkpoint =
                        checkpoint.copy(
                            handle =
                                checkpoint.handle.copy(
                                    scope = historyScope(wrong),
                                    partition = draftPartition(wrong),
                                )
                        )
                )
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) = flowOf(renamed)
                }
            assertIs<AppResult.Failed>(f.drafts(store).observe(wrong, f.conversation).first())
            assertEquals(raw, f.draftPage())
        }

    @Test
    fun missingKeyProtectsInactiveDraftsFromReplacementAndHistoryKeyRegeneration() =
        withDatabase { f ->
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, f.draft()))
            val raw = f.draftPage()
            f.keys.write(null)
            val other = f.access.copy(sessionId = "other-draft-account", accountId = historyId(999))
            f.activate(other)
            assertIs<AppResult.Failed>(f.drafts().replace(other, null, f.draft()))
            assertIs<AppResult.Failed>(
                f.repository.merge(other, f.conversation, f.room, listOf(f.event()))
            )
            assertNull(f.keys.read())
            f.activate()
            assertEquals(raw, f.draftPage())
            assertIs<AppResult.Failed>(f.drafts().get(f.access, f.conversation))
        }

    @Test
    fun lateOwnershipReplacementCannotAcknowledgeAnExactReplay() = withDatabase { f ->
        val draft = f.draft()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, draft))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    f.activate(f.access.copy(sessionId = "changed", accountId = historyId(998)))
                    return f.database.replicas.checkpoint(scope, partition)
                }
            }
        assertIs<AppResult.Failed>(f.drafts(store).replace(f.access, null, draft))
        f.activate()
        assertIs<AppResult.Failed>(f.drafts(store).get(f.access, f.conversation))
        f.activate()
        assertEquals(AppResult.Success(draft), f.drafts().get(f.access, f.conversation))
    }

    @Test
    fun unrelatedAutosaveDuringReadReturnsRetryableConflictWithoutPublishingAStalePage() =
        withDatabase { f ->
            val original = f.draft().copy(submissionId = historyId(980))
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, original))
            for (indexed in listOf(false, true)) {
                var changed = false
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override suspend fun checkpoint(
                            scope: ReplicaScope,
                            partition: String,
                        ): ReplicaCheckpoint? {
                            if (!changed) {
                                changed = true
                                val unrelated =
                                    f.draft()
                                        .copy(
                                            conversationId = historyId(if (indexed) 981 else 982),
                                            text =
                                                "An unrelated editor saved while this page was decrypted",
                                        )
                                assertEquals(
                                    AppResult.Success(true),
                                    f.drafts().replace(f.access, null, unrelated),
                                )
                            }
                            return f.database.replicas.checkpoint(scope, partition)
                        }
                    }
                val read =
                    if (indexed) f.drafts(store).prepared(f.access)
                    else f.drafts(store).get(f.access, f.conversation)
                val failure = assertIs<AppResult.Failed>(read).failure
                assertEquals(FailureKind.Conflict, failure.kind)
                assertEquals("client.collaboration_draft_busy", failure.code)
                assertTrue(changed)
                assertEquals(AppResult.Success(original), f.drafts().get(f.access, f.conversation))
                assertEquals(
                    listOf(original),
                    assertIs<AppResult.Success<CollaborationPreparedDraftPage>>(
                            f.drafts().prepared(f.access)
                        )
                        .value
                        .drafts,
                )
            }
        }

    @Test
    fun cancellingAPendingReadClosesTheOwnedSubscription() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flow {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        coroutineScope {
            val read = async { f.drafts(store).get(f.access, f.conversation) }
            entered.await()
            read.cancelAndJoin()
            assertTrue(stopped.isCompleted)
        }
        assertNull(f.keys.read())
    }

    @Test
    fun indexedPreparedPagesCross603MixedDraftsWithoutReadingEditingBodies() = withDatabase { f ->
        val repository = f.drafts()
        val all =
            (0 until 603).map { index ->
                f.draft(
                        text = if (index % 3 == 2) "" else "Draft $index",
                        submissionId = if (index % 3 == 0) historyId(20_000 + index) else null,
                    )
                    .copy(
                        conversationId = historyId(10_000 + index),
                        revision = historyId(30_000 + index),
                    )
            }
        for (draft in all) assertEquals(
            AppResult.Success(true),
            repository.replace(f.access, null, draft),
        )
        f.reopen()
        var decryptions = 0
        val cipher =
            object : dev.fajar.starter.securestorage.ContentCipher by f.cipher {
                override suspend fun decrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    ciphertext: ByteArray,
                ): ByteArray {
                    decryptions++
                    return f.cipher.decrypt(key, associatedData, ciphertext)
                }
            }
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("Prepared discovery must use its index")

                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = error("Prepared discovery must not decrypt every draft")

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    assertEquals(1, queries.size)
                    assertEquals(PREPARED_DRAFT_TAG, queries.single().tag)
                    assertEquals(9, queries.single().limit)
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }
            }
        val indexed = EncryptedCollaborationDraftRepository(store, cipher, f.keys)
        val collected = mutableListOf<CollaborationDraft>()
        var after: String? = null
        do {
            decryptions = 0
            val page =
                assertIs<AppResult.Success<CollaborationPreparedDraftPage>>(
                        indexed.prepared(f.access, after)
                    )
                    .value
            assertTrue(page.drafts.size <= 8)
            assertTrue(
                decryptions <= 10,
                "Only the checkpoint and at most nine indexed bodies may be decrypted",
            )
            collected += page.drafts
            after = page.next
        } while (after != null)
        assertEquals(
            all.filter { it.submissionId != null }.sortedBy { it.conversationId },
            collected,
        )
        assertEquals(201, collected.size)
        val first = collected.first()
        val cleared = first.copy(revision = historyId(40_000), text = "", submissionId = null)
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, first, cleared))
        val page =
            assertIs<AppResult.Success<CollaborationPreparedDraftPage>>(indexed.prepared(f.access))
                .value
        assertFalse(page.drafts.any { it.conversationId == first.conversationId })
        assertEquals(AppResult.Success(all[1]), f.drafts().get(f.access, all[1].conversationId))
        val wrong = f.access.copy(companyId = historyId(50_000))
        assertEquals(
            AppResult.Success(CollaborationPreparedDraftPage(emptyList(), null)),
            indexed.prepared(wrong),
        )
    }

    @Test
    fun competingDifferentConversationsRetainBothWithBoundedCheckpointRetry() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, f.draft()))
        val release = CompletableDeferred<Unit>()
        val barrier = Mutex()
        var commits = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    barrier.withLock { if (++commits == 2) release.complete(Unit) }
                    release.await()
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                    )
                }
            }
        val one = f.draft().copy(conversationId = historyId(51_000))
        val two = f.draft().copy(conversationId = historyId(51_001), text = "Another conversation")
        val results = coroutineScope {
            listOf(
                    async { f.drafts(store).replace(f.access, null, one) },
                    async { f.drafts(store).replace(f.access, null, two) },
                )
                .awaitAll()
        }
        assertEquals(listOf(AppResult.Success(true), AppResult.Success(true)), results)
        assertEquals(3, commits)
        assertEquals(AppResult.Success(one), f.drafts().get(f.access, one.conversationId))
        assertEquals(AppResult.Success(two), f.drafts().get(f.access, two.conversationId))
        assertEquals(AppResult.Success(f.draft()), f.drafts().get(f.access, f.conversation))
    }

    @Test
    fun frozenIndexCommitsWithItsBodyEvenWhenTheCommitReplyIsLost() = withDatabase { f ->
        val editing = f.draft()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, editing))
        var commits = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    commits++
                    assertTrue(
                        f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                        )
                    )
                    error("Lost commit reply")
                }
            }
        val frozen = editing.copy(revision = historyId(52_000), submissionId = editing.revision)
        assertIs<AppResult.Failed>(f.drafts(store).replace(f.access, editing, frozen))
        assertEquals(
            AppResult.Success(CollaborationPreparedDraftPage(listOf(frozen), null)),
            f.drafts().prepared(f.access),
        )
        assertEquals(AppResult.Success(true), f.drafts(store).replace(f.access, editing, frozen))
        assertEquals(1, commits)
    }

    @Test
    fun failedCasHasAFiniteBudgetAndInvalidDiscoveryDoesNoStorageWork() = withDatabase { f ->
        val editing = f.draft()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, editing))
        var commits = 0
        var rejectCommit = true
        var queryCalls = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    commits++
                    return if (rejectCommit) false
                    else
                        f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                        )
                }

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    queryCalls++
                    error("Invalid input must be rejected first")
                }
            }
        val repository = f.drafts(store)
        val replacement = editing.copy(revision = historyId(53_000), text = "Changed")
        val busy = assertIs<AppResult.Failed>(repository.replace(f.access, editing, replacement))
        assertEquals(FailureKind.Conflict, busy.failure.kind)
        assertEquals("client.collaboration_draft_busy", busy.failure.code)
        assertEquals(3, commits)
        assertEquals(AppResult.Success(editing), f.drafts().get(f.access, f.conversation))
        rejectCommit = false
        assertEquals(AppResult.Success(true), repository.replace(f.access, editing, replacement))
        assertEquals(4, commits)
        assertEquals(AppResult.Success(replacement), f.drafts().get(f.access, f.conversation))
        assertEquals(
            AppResult.Success(replacement),
            repository.get(f.access, editing.conversationId),
        )
        for (limit in listOf(0, 9, Int.MAX_VALUE)) assertIs<AppResult.Failed>(
            repository.prepared(f.access, limit = limit)
        )
        assertIs<AppResult.Failed>(repository.prepared(f.access, afterConversationId = "bad"))
        assertEquals(0, queryCalls)
    }

    @Test
    fun malformedPreparedLookaheadCannotPublishAnApparentlyValidFirstPage() = withDatabase { f ->
        val repository = f.drafts()
        for (index in 0..8) {
            val draft =
                f.draft(submissionId = historyId(54_000 + index))
                    .copy(conversationId = historyId(55_000 + index))
            assertEquals(AppResult.Success(true), repository.replace(f.access, null, draft))
        }
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ) =
                    f.database.replicas.observeQueries(scope, partition, queries).map { page ->
                        val rows = page.pages.single()
                        assertEquals(9, rows.size)
                        page.copy(
                            pages = listOf(rows.dropLast(1) + rows.last().copy(tags = emptySet()))
                        )
                    }
            }
        assertIs<AppResult.Failed>(f.drafts(store).prepared(f.access))
    }

    @Test
    fun reviewHoldRetainsOriginalAcrossReopenButLeavesTheAutomaticIndex() = withDatabase { f ->
        val frozen = f.draft(submissionId = historyId(60_000))
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, frozen))
        val held = frozen.copy(revision = historyId(60_001), reviewRequired = true)
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, frozen, held))
        f.reopen()
        assertEquals(AppResult.Success(held), f.drafts().get(f.access, f.conversation))
        assertEquals(
            AppResult.Success(CollaborationPreparedDraftPage(emptyList(), null)),
            f.drafts().prepared(f.access),
        )
        assertIs<AppResult.Failed>(
            f.drafts()
                .replace(
                    f.access,
                    held,
                    held.copy(revision = historyId(60_002), submissionId = null),
                )
        )
        assertEquals(AppResult.Success(held), f.drafts().get(f.access, f.conversation))
    }

    @Test
    fun recoveryCursorReopensIndependentlyAndRejectsCompetingGenerationOrLostKey() =
        withDatabase { f ->
            fun repository() =
                dev.fajar.starter.collaboration.data.repositories
                    .EncryptedCollaborationDraftRecoveryCursorRepository(
                        f.database.replicas,
                        f.cipher,
                        f.keys,
                    )
            assertEquals(AppResult.Success(null), repository().current(f.access))
            val first =
                assertIs<AppResult.Success<CollaborationDraftRecoveryCursor?>>(
                        repository().advance(f.access, null, historyId(61_000))
                    )
                    .value!!
            f.reopen()
            assertEquals(AppResult.Success(first), repository().current(f.access))
            val second =
                assertIs<AppResult.Success<CollaborationDraftRecoveryCursor?>>(
                        repository().advance(f.access, first, historyId(61_001))
                    )
                    .value!!
            assertEquals(
                AppResult.Success(null),
                repository().advance(f.access, first, historyId(61_002)),
            )
            assertNotEquals(first.generation, second.generation)
            val delivery =
                dev.fajar.starter.collaboration.data.repositories
                    .EncryptedCollaborationDeliveryCursorRepository(
                        f.database.replicas,
                        f.cipher,
                        f.keys,
                    )
            assertEquals(AppResult.Success(null), delivery.current(f.access))
            f.keys.write(null)
            val other = f.access.copy(sessionId = "other-recovery", accountId = historyId(61_003))
            f.activate(other)
            assertIs<AppResult.Failed>(repository().advance(other, null, historyId(61_004)))
            assertNull(f.keys.read())
        }

    private fun CollaborationHistoryFixture.drafts(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationDraftRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.draft(
        text: String = "Unsent draft",
        submissionId: String? = null,
    ) =
        CollaborationDraft(
            conversation,
            historyId(1001),
            text,
            Instant.fromEpochMilliseconds(1_793_491_200_000),
            submissionId,
        )

    private suspend fun CollaborationHistoryFixture.draftPage() =
        database.replicas
            .observeDocument(historyScope(access), draftPartition(access), conversation)
            .first()

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
