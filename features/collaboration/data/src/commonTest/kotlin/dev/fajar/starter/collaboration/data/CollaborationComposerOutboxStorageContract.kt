package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationComposerOutboxStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun originalAndNextEditorCommitTogetherAndSurviveLostReplyAndReopen() = withDatabase { f ->
        val submission = f.submission()
        f.save(submission.original)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    assertEquals(2, documents.size)
                    assertTrue(
                        f.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                        )
                    )
                    error("Simulated lost local commit response")
                }
            }
        assertIs<AppResult.Failed>(f.outbox(store).handoff(f.access, submission))
        val key = f.keys.read()
        f.reopen()
        assertEquals(AppResult.Success(submission), f.outbox().get(f.access, submission.id))
        assertEquals(
            AppResult.Success(submission.nextDraft),
            f.drafts().get(f.access, f.conversation),
        )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
            f.outbox().handoff(f.access, submission),
        )
        assertEquals(key, f.keys.read())
        val raw =
            f.database.replicas.observe(historyScope(f.access), draftPartition(f.access)).first()
        assertEquals(2, raw.documents.size)
        for (document in raw.documents) {
            assertFalse(document.payload.orEmpty().contains(submission.original.text))
            assertFalse(document.payload.orEmpty().contains(submission.original.replyToEventId!!))
        }
    }

    @Test
    fun retriesAndRetirementCannotReplaceSubsequentTextOrCreateAnotherOriginal() =
        withDatabase { f ->
            val submission = f.submission()
            f.save(submission.original)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, submission),
            )
            val next =
                submission.nextDraft.copy(
                    revision = historyId(600),
                    text = "Next message still being typed",
                )
            assertEquals(
                AppResult.Success(true),
                f.drafts().replace(f.access, submission.nextDraft, next),
            )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.outbox().handoff(f.access, submission),
            )
            assertEquals(AppResult.Success(next), f.drafts().get(f.access, f.conversation))
            assertEquals(
                AppResult.Success(false),
                f.outbox().remove(f.access, submission.copy(nextRevision = historyId(601))),
            )
            assertEquals(AppResult.Success(true), f.outbox().remove(f.access, submission))
            assertEquals(AppResult.Success(false), f.outbox().remove(f.access, submission))
            assertEquals(AppResult.Success(null), f.outbox().get(f.access, submission.id))
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
                f.outbox().handoff(f.access, submission),
            )
            assertEquals(AppResult.Success(next), f.drafts().get(f.access, f.conversation))
            assertTrue(f.pending().submissions.isEmpty())
        }

    @Test
    fun competingHandoffsKeepOnlyOneOriginalAndRejectChangedPayloadUnderItsId() =
        withDatabase { f ->
            val first = f.submission()
            val second = first.copy(nextRevision = historyId(602))
            f.save(first.original)
            val results = coroutineScope {
                listOf(
                        async { f.outbox().handoff(f.access, first) },
                        async { f.outbox().handoff(f.access, second) },
                    )
                    .awaitAll()
            }
            assertEquals(
                1,
                results.count { it == AppResult.Success(CollaborationComposerHandoffResult.STORED) },
            )
            assertEquals(
                1,
                results.count {
                    it == AppResult.Success(CollaborationComposerHandoffResult.CHANGED)
                },
            )
            val retained = f.pending().submissions.single()
            assertTrue(retained == first || retained == second)
            assertEquals(
                AppResult.Success(retained.nextDraft),
                f.drafts().get(f.access, f.conversation),
            )
            val changed =
                retained.copy(original = retained.original.copy(text = "Different original"))
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
                f.outbox().handoff(f.access, changed),
            )
        }

    @Test
    fun changedOwnerAtCommitLeavesOriginalAndOutboxUntouched() = withDatabase { f ->
        val submission = f.submission()
        f.save(submission.original)
        val nextOwner = f.access.copy(sessionId = "replacement", accountId = historyId(700))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    f.activate(nextOwner)
                    return f.database.replicas.commit(
                        handle,
                        expectedRevision,
                        checkpoint,
                        documents,
                        publish,
                    )
                }
            }
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
            f.outbox(store).handoff(f.access, submission),
        )
        assertEquals(AppResult.Success(null), f.outbox().get(nextOwner, submission.id))
        f.activate()
        assertEquals(
            AppResult.Success(submission.original),
            f.drafts().get(f.access, f.conversation),
        )
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, submission.id))
    }

    @Test
    fun renewedLoginRetainsBothDocumentsButOldLoginCannotReadOrRemove() = withDatabase { f ->
        val submission = f.submission()
        f.save(submission.original)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, submission),
        )
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, submission.id))
        assertEquals(AppResult.Success(false), f.outbox().remove(f.access, submission))
        assertEquals(AppResult.Success(submission), f.outbox().get(renewed, submission.id))
        assertEquals(
            AppResult.Success(submission.nextDraft),
            f.drafts().get(renewed, f.conversation),
        )
    }

    @Test
    fun cancellationBeforeCommitDoesNotClearInputAndReleasesSubscription() = withDatabase { f ->
        val submission = f.submission()
        f.save(submission.original)
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
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
                        closed.complete(Unit)
                    }
                }
            }
        coroutineScope {
            val operation = async { f.outbox(store).handoff(f.access, submission) }
            entered.await()
            operation.cancelAndJoin()
        }
        assertTrue(closed.isCompleted)
        assertEquals(
            AppResult.Success(submission.original),
            f.drafts().get(f.access, f.conversation),
        )
        assertTrue(f.pending().submissions.isEmpty())
    }

    @Test
    fun cancellationAfterCommitLeavesRecoverableOriginalWithoutReturningSuccess() =
        withDatabase { f ->
            val submission = f.submission()
            f.save(submission.original)
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                    ): Boolean {
                        assertTrue(
                            f.database.replicas.commit(
                                handle,
                                expectedRevision,
                                checkpoint,
                                documents,
                                publish,
                            )
                        )
                        committed.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return true
                    }
                }
            coroutineScope {
                val operation = async { f.outbox(store).handoff(f.access, submission) }
                committed.await()
                operation.cancel()
                release.complete(Unit)
                operation.join()
                assertTrue(operation.isCancelled)
            }
            f.reopen()
            assertEquals(AppResult.Success(submission), f.outbox().get(f.access, submission.id))
            assertEquals(
                AppResult.Success(submission.nextDraft),
                f.drafts().get(f.access, f.conversation),
            )
        }

    @Test
    fun checkpointContentionHasABoundedRetryAndDoesNotClaimTheTargetChanged() = withDatabase { f ->
        val submission = f.submission()
        f.save(submission.original)
        var attempts = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    attempts++
                    return false
                }
            }
        val result = assertIs<AppResult.Failed>(f.outbox(store).handoff(f.access, submission))
        assertEquals("client.collaboration_composer_busy", result.failure.code)
        assertEquals(3, attempts)
        assertEquals(
            AppResult.Success(submission.original),
            f.drafts().get(f.access, f.conversation),
        )
        assertTrue(f.pending().submissions.isEmpty())
    }

    @Test
    fun indexedPagesKeepMultipleOriginalsForOneConversationWithoutReadingDraftBodies() =
        withDatabase { f ->
            val originals = mutableListOf<CollaborationComposerSubmission>()
            var previous: CollaborationDraft? = null
            repeat(19) { index ->
                val submission = f.submission(index)
                assertEquals(
                    AppResult.Success(true),
                    f.drafts().replace(f.access, previous, submission.original),
                )
                assertEquals(
                    AppResult.Success(CollaborationComposerHandoffResult.STORED),
                    f.outbox().handoff(f.access, submission),
                )
                originals += submission
                previous = submission.nextDraft
            }
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): Flow<ReplicaPage> = error("Do not scan editing bodies")

                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        assertEquals(COMPOSER_OUTBOX_TAG, queries.single().tag)
                        assertEquals(9, queries.single().limit)
                        return f.database.replicas.observeQueries(scope, partition, queries)
                    }
                }
            val found = mutableListOf<CollaborationComposerSubmission>()
            var after: String? = null
            do {
                val page =
                    assertIs<AppResult.Success<CollaborationComposerSubmissionPage>>(
                            f.outbox(store).pending(f.access, after)
                        )
                        .value
                assertTrue(page.submissions.size <= 8)
                found += page.submissions
                after = page.next
            } while (after != null)
            assertEquals(originals.sortedBy { it.id }, found)
            assertTrue(
                assertIs<AppResult.Success<CollaborationPreparedDraftPage>>(
                        f.drafts().prepared(f.access)
                    )
                    .value
                    .drafts
                    .isEmpty()
            )
        }

    @Test
    fun fullOutboxRejectsHandoffWithoutLosingTheCurrentText() = withDatabase { f ->
        var previous: CollaborationDraft? = null
        repeat(128) { index ->
            val submission = f.submission(index)
            assertEquals(
                AppResult.Success(true),
                f.drafts().replace(f.access, previous, submission.original),
            )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, submission),
            )
            previous = submission.nextDraft
        }
        val overflow = f.submission(128)
        assertEquals(
            AppResult.Success(true),
            f.drafts().replace(f.access, previous, overflow.original),
        )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CAPACITY_EXCEEDED),
            f.outbox().handoff(f.access, overflow),
        )
        assertEquals(AppResult.Success(overflow.original), f.drafts().get(f.access, f.conversation))
        assertEquals(AppResult.Success(null), f.outbox().get(f.access, overflow.id))
    }

    @Test
    fun legacyFrozenDraftRemainsRecoverableAndCannotBeHandedOffAgain() = withDatabase { f ->
        val submission = f.submission()
        val frozen = submission.original.copy(submissionId = historyId(800))
        f.save(frozen)
        assertIs<AppResult.Failed>(f.outbox().handoff(f.access, submission.copy(original = frozen)))
        assertEquals(
            listOf(frozen),
            assertIs<AppResult.Success<CollaborationPreparedDraftPage>>(
                    f.drafts().prepared(f.access)
                )
                .value
                .drafts,
        )
        assertTrue(f.pending().submissions.isEmpty())
        assertEquals(AppResult.Success(frozen), f.drafts().get(f.access, f.conversation))
    }

    @Test
    fun corruptOriginalOrLostKeyDoesNotBecomeAbsenceOrCreateAReplacementKey() = withDatabase { f ->
        val submission = f.submission()
        f.save(submission.original)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, submission),
        )
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> =
                    f.database.replicas.observeDocument(scope, partition, id).map { page ->
                        page.copy(documents = page.documents.map { it.copy(payload = "malformed") })
                    }
            }
        assertIs<AppResult.Failed>(f.outbox(store).get(f.access, submission.id))
        assertIs<AppResult.Failed>(f.outbox(store).remove(f.access, submission))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.outbox().get(f.access, submission.id))
        assertIs<AppResult.Failed>(f.outbox().handoff(f.access, submission))
        assertNull(f.keys.read())
    }

    private fun CollaborationHistoryFixture.submission(index: Int = 0) =
        CollaborationComposerSubmission(
            CollaborationDraft(
                conversation,
                historyId(10_000 + index),
                " Original $index\n🙂 ",
                Instant.fromEpochMilliseconds(1_793_491_200_000L + index),
                replyToEventId = "\$private-quote",
            ),
            historyId(20_000 + index),
            Instant.fromEpochMilliseconds(1_793_491_201_000L + index),
        )

    private fun CollaborationHistoryFixture.drafts() =
        EncryptedCollaborationDraftRepository(database.replicas, cipher, keys)

    private fun CollaborationHistoryFixture.outbox(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationComposerOutboxRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.save(draft: CollaborationDraft) {
        assertEquals(AppResult.Success(true), drafts().replace(access, null, draft))
    }

    private suspend fun CollaborationHistoryFixture.pending() =
        assertIs<AppResult.Success<CollaborationComposerSubmissionPage>>(outbox().pending(access))
            .value

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
