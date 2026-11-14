package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.spaceHistoryScope
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRecipientOutboxStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun multipleOfflineSendsKeepEveryOriginalAndReopenWithAnEmptyEditor() = withDatabase { f ->
        var previous: CollaborationSpaceRecipientDraft? = null
        val sent = (0..3).map { f.submission(it, listOf("AAA", "BBB", "CCC", "DDD")[it]) }
        for (value in sent) {
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, previous, value),
            )
            previous = value.nextDraft
        }
        val key = f.keys.read()
        f.reopen()
        val page =
            assertIs<AppResult.Success<CollaborationSpaceRecipientSubmissionPage>>(
                    f.outbox().pending(f.access)
                )
                .value
        assertEquals(sent, page.submissions)
        assertNull(page.next)
        assertEquals(
            AppResult.Success(previous),
            f.drafts().get(f.access, sent.first().original.peerAccountId),
        )
        for (value in sent) assertEquals(
            AppResult.Success(value),
            f.outbox().get(f.access, value.id),
        )
        assertEquals(key, f.keys.read())
        assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun replayPreservesLaterTypingAndDoesNotReplaceRecoveryWake() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val later = sent.nextDraft.copy(revision = historyId(9400), text = "Unsent next message")
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, sent.nextDraft, later))
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        val checkpoint =
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                recipientDraftPartition(f.access),
            )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
            f.outbox().handoff(f.access, null, sent),
        )
        assertEquals(AppResult.Success(later), f.drafts().get(f.access, later.peerAccountId))
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        assertEquals(
            checkpoint,
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                recipientDraftPartition(f.access),
            ),
        )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
            f.outbox()
                .handoff(
                    f.access,
                    null,
                    sent.copy(original = sent.original.copy(text = "Changed original")),
                ),
        )
    }

    @Test
    fun lostWriteReplyRetainsOriginalEditorAndWakeAsOneTransaction() = withDatabase { f ->
        val sent = f.submission()
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
                    error("Lost original write reply")
                }
            }
        assertIs<AppResult.Failed>(f.outbox(store).handoff(f.access, null, sent))
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        f.reopen()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
            f.outbox().handoff(f.access, null, sent),
        )
        assertEquals(
            AppResult.Success(sent.nextDraft),
            f.drafts().get(f.access, sent.original.peerAccountId),
        )
        assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun competingFirstSendsRetainOnlyTheWinningOriginalAndEditor() = withDatabase { f ->
        val released = CompletableDeferred<Unit>()
        val lock = Mutex()
        var arrivals = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    lock.withLock { if (++arrivals == 2) released.complete(Unit) }
                    released.await()
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        val values = listOf(f.submission(), f.submission(1))
        val results = coroutineScope {
            values.map { async { f.outbox(store).handoff(f.access, null, it) } }.awaitAll()
        }
        assertEquals(
            1,
            results.count { it == AppResult.Success(CollaborationComposerHandoffResult.STORED) },
        )
        assertEquals(
            1,
            results.count { it == AppResult.Success(CollaborationComposerHandoffResult.CHANGED) },
        )
        f.reopen()
        val winner =
            assertIs<AppResult.Success<CollaborationSpaceRecipientSubmissionPage>>(
                    f.outbox().pending(f.access)
                )
                .value
                .submissions
                .single()
        assertTrue(winner in values)
        assertEquals(
            AppResult.Success(winner.nextDraft),
            f.drafts().get(f.access, winner.original.peerAccountId),
        )
    }

    @Test
    fun fullWakeStoreRejectsWithoutClearingTheEditorOrRetainingHalfASend() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, sent.original))
        repeat(32) { assertNotNull(f.database.workWakes.request("occupied-$it", "generation", 32)) }
        val before =
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                recipientDraftPartition(f.access),
            )
        var attempts = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    attempts++
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                        wake,
                    )
                }
            }
        assertIs<AppResult.Failed>(f.outbox(store).handoff(f.access, sent.original, sent))
        assertEquals(3, attempts)
        assertEquals(
            before,
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                recipientDraftPartition(f.access),
            ),
        )
        assertEquals(
            AppResult.Success(sent.original),
            f.drafts().get(f.access, sent.original.peerAccountId),
        )
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, sent.id))
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun boundedPagesPreserveAllOriginalsAndCapacityRejectionKeepsCurrentTyping() =
        withDatabase { f ->
            val values = (0 until 128).map { f.submission(it) }
            var editor: CollaborationSpaceRecipientDraft? = null
            for (value in values) {
                assertEquals(
                    AppResult.Success(CollaborationComposerHandoffResult.STORED),
                    f.outbox().handoff(f.access, editor, value),
                )
                editor = value.nextDraft
            }
            val rejected = f.submission(128)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.CAPACITY_EXCEEDED),
                f.outbox().handoff(f.access, editor, rejected),
            )
            assertEquals(
                AppResult.Success(editor),
                f.drafts().get(f.access, rejected.original.peerAccountId),
            )
            assertEquals(AppResult.Success(null), f.outbox().get(f.access, rejected.id))
            val pages = mutableListOf<CollaborationSpaceRecipientSubmission>()
            var after: String? = null
            var queries = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        assertEquals(1, queries.size)
                        assertEquals(9, queries.single().limit)
                        assertEquals(RECIPIENT_OUTBOX_TAG, queries.single().tag)
                        return f.database.replicas.observeQueries(scope, partition, queries)
                    }
                }
            do {
                val page =
                    assertIs<AppResult.Success<CollaborationSpaceRecipientSubmissionPage>>(
                            f.outbox(store).pending(f.access, after, 8)
                        )
                        .value
                pages += page.submissions
                after = page.next
                assertTrue(++queries <= 16)
            } while (after != null)
            assertEquals(values, pages)
            assertEquals(16, queries)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.outbox().handoff(f.access, null, values.first()),
            )
        }

    @Test
    fun invalidInputAndEmptyReadsDoNotAllocateContentKeys() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, historyId(9300)))
        assertTrue(
            assertIs<AppResult.Success<CollaborationSpaceRecipientSubmissionPage>>(
                    f.outbox().pending(f.access)
                )
                .value
                .submissions
                .isEmpty()
        )
        val sent = f.submission()
        for (invalid in
            listOf(
                sent.copy(nextRevision = sent.id),
                sent.copy(original = sent.original.copy(text = " ")),
                sent.copy(original = sent.original.copy(peerAccountId = f.access.accountId)),
                sent.copy(submittedAt = Instant.fromEpochMilliseconds(-1)),
            )) assertIs<AppResult.Failed>(f.outbox().handoff(f.access, null, invalid))
        assertIs<AppResult.Failed>(f.outbox().pending(f.access, limit = 9))
        assertIs<AppResult.Failed>(f.outbox().pending(f.access, after = "invalid"))
        assertNull(f.keys.read())
    }

    @Test
    fun groupsAndAccountsStayIsolatedAndMissingKeysNeverRegenerate() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val other = f.access.copy(spaceId = historyId(9500))
        assertEquals(AppResult.Success(null), f.outbox().get(other, sent.id))
        val another = sent.copy(original = sent.original.copy(text = "Other Group"))
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(other, null, another),
        )
        assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        assertEquals(AppResult.Success(sent), f.outbox().get(renewed, sent.id))
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, sent.id))
        val different = renewed.copy(accountId = historyId(9501), sessionId = "different-account")
        f.activate(different)
        assertEquals(AppResult.Success(null), f.outbox().get(different, sent.id))
        f.activate(renewed)
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.outbox().get(renewed, sent.id))
        assertIs<AppResult.Failed>(f.outbox().handoff(renewed, sent.nextDraft, f.submission(1)))
        assertNull(f.keys.read())
    }

    @Test
    fun modifiedOriginalFramesAndDuplicateRowsFailWithoutPublishingText() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val raw =
            f.database.replicas
                .observeDocument(
                    spaceHistoryScope(f.access),
                    recipientDraftPartition(f.access),
                    recipientSubmissionId(sent.id),
                )
                .first()
        val document = raw.documents.single()
        val checkpoint = requireNotNull(raw.checkpoint)
        val changes =
            listOf(
                raw.copy(checkpoint = null),
                raw.copy(documents = listOf(document, document)),
                raw.copy(
                    documents = listOf(document.copy(id = recipientSubmissionId(historyId(9990))))
                ),
                raw.copy(documents = listOf(document.copy(sortKey = "wrong"))),
                raw.copy(documents = listOf(document.copy(version = document.version + 1))),
                raw.copy(documents = listOf(document.copy(tags = emptySet()))),
                raw.copy(documents = listOf(document.copy(payload = "broken"))),
                raw.copy(
                    checkpoint =
                        checkpoint.copy(
                            handle = checkpoint.handle.copy(generation = historyId(9991))
                        )
                ),
            )
        for (changed in changes) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ) = flowOf(changed)
                }
            assertIs<AppResult.Failed>(f.outbox(store).get(f.access, sent.id))
        }
        assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
    }

    @Test
    fun cancellationClosesThePendingReaderWithoutWritingOrAllocatingAKey() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        var closed = 0
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
                        closed++
                    }
                }
            }
        coroutineScope {
            val read = launch { f.outbox(store).get(f.access, f.submission().id) }
            entered.await()
            read.cancelAndJoin()
        }
        assertEquals(1, closed)
        assertNull(f.keys.read())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun cancellationDuringWriteKeepsThePreviousEditorAndNoOriginalOrWake() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, sent.original))
        val entered = CompletableDeferred<Unit>()
        var closed = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        closed++
                    }
                }
            }
        coroutineScope {
            val write = launch { f.outbox(store).handoff(f.access, sent.original, sent) }
            entered.await()
            write.cancelAndJoin()
        }
        assertEquals(1, closed)
        f.reopen()
        assertEquals(
            AppResult.Success(sent.original),
            f.drafts().get(f.access, sent.original.peerAccountId),
        )
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, sent.id))
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun rawSnapshotsAreDetachedBeforeCipherSuspension() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val raw =
            f.database.replicas
                .observeDocument(
                    spaceHistoryScope(f.access),
                    recipientDraftPartition(f.access),
                    recipientSubmissionId(sent.id),
                )
                .first()
        val tags = mutableSetOf(RECIPIENT_OUTBOX_TAG)
        val documents = mutableListOf(raw.documents.single().copy(tags = tags))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
                    flowOf(raw.copy(documents = documents))
            }
        val contentCipher =
            object : ContentCipher by f.cipher {
                override suspend fun decrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    ciphertext: ByteArray,
                ): ByteArray {
                    documents.clear()
                    tags.clear()
                    yield()
                    return f.cipher.decrypt(key, associatedData, ciphertext)
                }
            }
        assertEquals(AppResult.Success(sent), f.outbox(store, contentCipher).get(f.access, sent.id))
    }

    @Test
    fun competingRecipientCheckpointsStopAfterThreeAttemptsWithoutLosingTyping() =
        withDatabase { f ->
            val sent = f.submission()
            assertEquals(AppResult.Success(true), f.drafts().replace(f.access, null, sent.original))
            var unrelated: CollaborationSpaceRecipientDraft? = null
            var attempts = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        attempts++
                        val next =
                            sent.original.copy(
                                peerAccountId = historyId(9800),
                                revision = historyId(9800 + attempts),
                                text = "Other $attempts",
                            )
                        assertEquals(
                            AppResult.Success(true),
                            f.drafts().replace(f.access, unrelated, next),
                        )
                        unrelated = next
                        return f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    }
                }
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.outbox(store).handoff(f.access, sent.original, sent))
                    .failure
                    .kind,
            )
            assertEquals(3, attempts)
            assertEquals(
                AppResult.Success(sent.original),
                f.drafts().get(f.access, sent.original.peerAccountId),
            )
            assertEquals(AppResult.Success(null), f.outbox().get(f.access, sent.id))
        }

    @Test
    fun removingOneOriginalPreservesLaterTypingOtherMessagesAndWakeAcrossRestart() =
        withDatabase { f ->
            val first = f.submission()
            val second = f.submission(1)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, null, first),
            )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, first.nextDraft, second),
            )
            val later =
                second.nextDraft.copy(revision = historyId(9900), text = "Independent typing")
            assertEquals(
                AppResult.Success(true),
                f.drafts().replace(f.access, second.nextDraft, later),
            )
            val scope = spaceHistoryScope(f.access)
            val partition = recipientDraftPartition(f.access)
            val before = f.database.replicas.observe(scope, partition, limit = 128).first()
            val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
            val key = f.keys.read()
            assertEquals(AppResult.Success(true), f.outbox().remove(f.access, first))
            f.reopen()
            assertEquals(AppResult.Success(null), f.outbox().get(f.access, first.id))
            assertEquals(AppResult.Success(second), f.outbox().get(f.access, second.id))
            assertEquals(AppResult.Success(later), f.drafts().get(f.access, later.peerAccountId))
            val after = f.database.replicas.observe(scope, partition, limit = 128).first()
            assertEquals(
                before.documents.filter {
                    it.id !in setOf(recipientSubmissionId(first.id), RECIPIENT_OUTBOX_VERSION_ID)
                },
                after.documents.filter { it.id != RECIPIENT_OUTBOX_VERSION_ID },
            )
            assertEquals(
                AppResult.Success(
                    CollaborationSpaceRecipientOutboxVersion(
                        after.checkpoint!!.handle.generation,
                        after.checkpoint!!.revision,
                    )
                ),
                f.outbox().observeVersion(f.access).first(),
            )
            assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
            assertEquals(key, f.keys.read())
        }

    @Test
    fun removalComparesTheCompleteOriginalAndAbsentReplayDoesNotWrite() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val changed =
            listOf(
                sent.copy(original = sent.original.copy(text = "Different")),
                sent.copy(nextRevision = historyId(9997)),
                sent.copy(
                    submittedAt =
                        Instant.fromEpochMilliseconds(sent.submittedAt.toEpochMilliseconds() + 1)
                ),
            )
        val scope = spaceHistoryScope(f.access)
        val partition = recipientDraftPartition(f.access)
        val before = f.database.replicas.checkpoint(scope, partition)
        for (value in changed) assertEquals(
            AppResult.Success(false),
            f.outbox().remove(f.access, value),
        )
        assertEquals(before, f.database.replicas.checkpoint(scope, partition))
        assertEquals(AppResult.Success(true), f.outbox().remove(f.access, sent))
        val removed = f.database.replicas.checkpoint(scope, partition)
        assertEquals(AppResult.Success(false), f.outbox().remove(f.access, sent))
        assertEquals(removed, f.database.replicas.checkpoint(scope, partition))
    }

    @Test
    fun losingRemovalReplyStillRetainsDeletionAndIndependentEditor() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertNull(wake)
                    assertTrue(
                        f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    )
                    error("Lost removal response")
                }
            }
        assertIs<AppResult.Failed>(f.outbox(store).remove(f.access, sent))
        f.reopen()
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, sent.id))
        assertEquals(
            AppResult.Success(sent.nextDraft),
            f.drafts().get(f.access, sent.original.peerAccountId),
        )
        assertEquals(AppResult.Success(false), f.outbox().remove(f.access, sent))
    }

    @Test
    fun competingRemoversAcknowledgeOnlyOneCommit() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val arrived = CompletableDeferred<Unit>()
        val lock = Mutex()
        var commits = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    lock.withLock { if (++commits == 2) arrived.complete(Unit) }
                    arrived.await()
                    assertNull(wake)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                        wake,
                    )
                }
            }
        val results = coroutineScope {
            List(2) { async { f.outbox(store).remove(f.access, sent) } }.awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, sent.id))
    }

    @Test
    fun competingEditorStopsRemovalAfterThreeAttemptsAndRetainsBothFacts() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        var editor = sent.nextDraft
        var attempts = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    attempts++
                    val later =
                        editor.copy(
                            revision = historyId(9900 + attempts),
                            text = "Typing $attempts",
                        )
                    assertEquals(
                        AppResult.Success(true),
                        f.drafts().replace(f.access, editor, later),
                    )
                    editor = later
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                        wake,
                    )
                }
            }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.outbox(store).remove(f.access, sent)).failure.kind,
        )
        assertEquals(3, attempts)
        assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
        assertEquals(AppResult.Success(editor), f.drafts().get(f.access, editor.peerAccountId))
        assertEquals(AppResult.Success(true), f.outbox().remove(f.access, sent))
    }

    @Test
    fun removalCannotRegenerateLostKeyOrTouchAReplacementAccount() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val other = f.access.copy(accountId = historyId(9991), sessionId = "another-session")
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                        wake,
                    )
                }
            }
        assertEquals(AppResult.Success(false), f.outbox(store).remove(f.access, sent))
        f.activate(f.access)
        assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
        val checkpoint =
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                recipientDraftPartition(f.access),
            )
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.outbox().remove(f.access, sent))
        assertNull(f.keys.read())
        assertEquals(
            checkpoint,
            f.database.replicas.checkpoint(
                spaceHistoryScope(f.access),
                recipientDraftPartition(f.access),
            ),
        )
    }

    @Test
    fun cancellationBeforeAndAfterRemovalCommitPreservesTheActualTransaction() = withDatabase { f ->
        for (committed in listOf(false, true)) {
            val sent = f.submission(if (committed) 1 else 0)
            val editor =
                assertIs<AppResult.Success<CollaborationSpaceRecipientDraft?>>(
                        f.drafts().get(f.access, sent.original.peerAccountId)
                    )
                    .value
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, editor, sent),
            )
            val arrived = CompletableDeferred<Unit>()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        if (committed)
                            assertTrue(
                                f.database.replicas.commit(
                                    handle,
                                    expectedRevision,
                                    checkpoint,
                                    documents,
                                    publish,
                                    wake,
                                )
                            )
                        arrived.complete(Unit)
                        awaitCancellation()
                    }
                }
            coroutineScope {
                val task = launch { f.outbox(store).remove(f.access, sent) }
                arrived.await()
                task.cancelAndJoin()
            }
            f.reopen()
            assertEquals(
                AppResult.Success(if (committed) null else sent),
                f.outbox().get(f.access, sent.id),
            )
            assertEquals(
                AppResult.Success(sent.nextDraft),
                f.drafts().get(f.access, sent.original.peerAccountId),
            )
        }
    }

    @Test
    fun removalAuthenticatesTheCheckpointAndOriginalBeforeDeleting() = withDatabase { f ->
        val sent = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, null, sent),
        )
        val raw =
            f.database.replicas
                .observeDocument(
                    spaceHistoryScope(f.access),
                    recipientDraftPartition(f.access),
                    recipientSubmissionId(sent.id),
                )
                .first()
        val document = raw.documents.single()
        val changes =
            listOf(
                raw.copy(checkpoint = null),
                raw.copy(documents = listOf(document.copy(payload = "broken"))),
                raw.copy(documents = listOf(document, document)),
                raw.copy(checkpoint = raw.checkpoint!!.copy(payload = "broken")),
            )
        for (changed in changes) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun workingDocuments(
                        scope: ReplicaScope,
                        partition: String,
                        ids: List<String>,
                    ) = changed
                }
            assertIs<AppResult.Failed>(f.outbox(store).remove(f.access, sent))
        }
        assertEquals(AppResult.Success(sent), f.outbox().get(f.access, sent.id))
    }

    private fun CollaborationSpaceHistoryFixture.outbox(
        store: ReplicaStore = database.replicas,
        contentCipher: ContentCipher = cipher,
    ) = EncryptedCollaborationSpaceRecipientOutboxRepository(store, contentCipher, keys)

    private fun CollaborationSpaceHistoryFixture.drafts() =
        EncryptedCollaborationSpaceRecipientDraftRepository(database.replicas, cipher, keys)

    private fun CollaborationSpaceHistoryFixture.submission(
        index: Int = 0,
        text: String = "Message $index",
    ) =
        CollaborationSpaceRecipientSubmission(
            CollaborationSpaceRecipientDraft(
                historyId(9200),
                historyId(9300 + index * 2),
                text,
                Instant.fromEpochMilliseconds(1_793_491_200_000L + index),
            ),
            historyId(9301 + index * 2),
            Instant.fromEpochMilliseconds(1_793_491_200_000L + index),
        )

    private fun withDatabase(block: suspend (CollaborationSpaceHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationSpaceHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
