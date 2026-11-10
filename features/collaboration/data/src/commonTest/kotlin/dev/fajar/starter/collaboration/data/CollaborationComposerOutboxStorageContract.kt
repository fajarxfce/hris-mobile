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
    fun unrelatedEditingDoesNotInvalidateAnAuthenticatedOutboxSnapshot() = withDatabase { f ->
        val original = f.submission()
        f.save(original.original)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, original),
        )
        val next = original.nextDraft.copy(revision = historyId(700), text = "Next text")
        var changed = false
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> =
                    f.database.replicas.observeDocument(scope, partition, id).map { captured ->
                        if (!changed) {
                            changed = true
                            assertEquals(
                                AppResult.Success(true),
                                f.drafts().replace(f.access, original.nextDraft, next),
                            )
                        }
                        captured
                    }
            }
        assertEquals(AppResult.Success(original), f.outbox(store).get(f.access, original.id))
        assertTrue(changed)
        assertEquals(AppResult.Success(next), f.drafts().get(f.access, f.conversation))
    }

    @Test
    fun recoveryDiscoveryKeepsItsCoherentPageWhileAnotherEditorAdvancesThePartition() =
        withDatabase { f ->
            val original = f.submission()
            f.save(original.original)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, original),
            )
            val next = original.nextDraft.copy(revision = historyId(700), text = "Next text")
            var changed = false
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> =
                        f.database.replicas.observeQueries(scope, partition, queries).map { captured
                            ->
                            if (!changed) {
                                changed = true
                                assertEquals(
                                    AppResult.Success(true),
                                    f.drafts().replace(f.access, original.nextDraft, next),
                                )
                            }
                            captured
                        }
                }
            assertEquals(
                AppResult.Success(CollaborationComposerSubmissionPage(listOf(original), null)),
                f.outbox(store).pending(f.access),
            )
            assertTrue(changed)
            assertEquals(AppResult.Success(next), f.drafts().get(f.access, f.conversation))
        }

    @Test
    fun unsavedFirstMessageCreatesBothDocumentsAndRecoversALostInitialCommitReply() =
        withDatabase { f ->
            val submission = f.submission()
            assertNull(f.keys.read())
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertNull(expected)
                        assertEquals(2, assertNotNull(snapshot).documents.size)
                        assertTrue(
                            f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                        )
                        error("Simulated lost initial commit reply")
                    }
                }
            assertIs<AppResult.Failed>(
                f.outbox(store).handoff(f.access, submission, expected = null)
            )
            val key = assertNotNull(f.keys.read())
            f.reopen()
            assertEquals(AppResult.Success(submission), f.outbox().get(f.access, submission.id))
            assertEquals(
                AppResult.Success(submission.nextDraft),
                f.drafts().get(f.access, f.conversation),
            )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.outbox().handoff(f.access, submission, expected = null),
            )
            assertEquals(key, f.keys.read())
        }

    @Test
    fun unsavedChangesReplaceOnlyTheirExpectedStoredEditor() = withDatabase { f ->
        val original = f.submission()
        val saved =
            original.original.copy(revision = historyId(700), text = "Previously saved text")
        val sibling = saved.copy(conversationId = historyId(701), revision = historyId(702))
        f.save(saved)
        f.save(sibling)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, original, expected = saved),
        )
        assertEquals(AppResult.Success(original), f.outbox().get(f.access, original.id))
        assertEquals(
            AppResult.Success(original.nextDraft),
            f.drafts().get(f.access, f.conversation),
        )
        assertEquals(AppResult.Success(sibling), f.drafts().get(f.access, sibling.conversationId))
    }

    @Test
    fun changedStoredSlotRejectsAnUnsavedHandoffWithoutClearingNewerInput() = withDatabase { f ->
        val original = f.submission()
        val expected = original.original.copy(revision = historyId(700), text = "Earlier input")
        val newer = expected.copy(revision = historyId(701), text = "Newer input")
        f.save(newer)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
            f.outbox().handoff(f.access, original, expected = expected),
        )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
            f.outbox().handoff(f.access, original, expected = null),
        )
        assertEquals(AppResult.Success(newer), f.drafts().get(f.access, f.conversation))
        assertTrue(f.pending().submissions.isEmpty())
    }

    @Test
    fun competingUnsavedFirstMessagesKeepOneExactOriginalAndEditingRevision() = withDatabase { f ->
        val first = f.submission()
        val second = f.submission(1)
        val results = coroutineScope {
            listOf(
                    async { f.outbox().handoff(f.access, first, expected = null) },
                    async { f.outbox().handoff(f.access, second, expected = null) },
                )
                .awaitAll()
        }
        assertEquals(
            1,
            results.count { it == AppResult.Success(CollaborationComposerHandoffResult.STORED) },
        )
        assertEquals(
            1,
            results.count { it == AppResult.Success(CollaborationComposerHandoffResult.CHANGED) },
        )
        val retained = f.pending().submissions.single()
        assertTrue(retained == first || retained == second)
        assertEquals(
            AppResult.Success(retained.nextDraft),
            f.drafts().get(f.access, f.conversation),
        )
    }

    @Test
    fun unsavedHandoffCannotReplaceAFrozenOrAnotherConversationsSlot() = withDatabase { f ->
        val original = f.submission()
        val frozen = original.original.copy(submissionId = historyId(700))
        f.save(frozen)
        assertIs<AppResult.Failed>(f.outbox().handoff(f.access, original, expected = frozen))
        assertIs<AppResult.Failed>(
            f.outbox()
                .handoff(
                    f.access,
                    original,
                    expected = original.original.copy(conversationId = historyId(701)),
                )
        )
        assertEquals(AppResult.Success(frozen), f.drafts().get(f.access, f.conversation))
        assertTrue(f.pending().submissions.isEmpty())
    }

    @Test
    fun absentEditingPartitionDoesNotRecreateLostKeysOverAnotherCompanysRecords() =
        withDatabase { f ->
            val original = f.submission()
            f.save(original.original)
            f.keys.write(null)
            val other = f.access.copy(companyId = historyId(701))
            f.activate(other)
            assertIs<AppResult.Failed>(f.outbox().handoff(other, original, expected = null))
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(null), f.outbox().get(other, original.id))
            assertEquals(AppResult.Success(null), f.drafts().get(other, f.conversation))
        }

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
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertEquals(2, documents.size)
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
                    wake: WorkWakeRequest?,
                ): Boolean {
                    f.activate(nextOwner)
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
                        wake: WorkWakeRequest?,
                    ): Boolean {
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
                    wake: WorkWakeRequest?,
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

    @Test
    fun heldOriginalSurvivesReopenAndReplayWithoutReenteringAutomaticRecovery() =
        withDatabase { f ->
            val original = f.submission()
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, original, null),
            )
            val next = original.nextDraft.copy(revision = historyId(700), text = "Next input")
            assertEquals(
                AppResult.Success(true),
                f.drafts().replace(f.access, original.nextDraft, next),
            )
            assertEquals(AppResult.Success(true), f.outbox().hold(f.access, original))
            f.reopen()
            assertEquals(AppResult.Success(original), f.outbox().get(f.access, original.id))
            assertTrue(f.pending().submissions.isEmpty())
            assertEquals(
                AppResult.Success(listOf(CollaborationComposerOutboxEntry(original, true))),
                f.outbox().observe(f.access).first(),
            )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.outbox().handoff(f.access, original, null),
            )
            assertTrue(f.pending().submissions.isEmpty())
            assertEquals(AppResult.Success(next), f.drafts().get(f.access, f.conversation))
            assertEquals(AppResult.Success(true), f.outbox().remove(f.access, original))
            assertEquals(AppResult.Success(emptyList()), f.outbox().observe(f.access).first())
        }

    @Test
    fun holdRequiresTheExactOriginalAndCannotHideDifferentContent() = withDatabase { f ->
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, original, null),
        )
        assertEquals(
            AppResult.Success(false),
            f.outbox()
                .hold(
                    f.access,
                    original.copy(original = original.original.copy(text = "Different")),
                ),
        )
        assertEquals(listOf(original), f.pending().submissions)
        assertEquals(AppResult.Success(true), f.outbox().hold(f.access, original))
        assertEquals(AppResult.Success(true), f.outbox().hold(f.access, original))
    }

    @Test
    fun lostHoldCommitReplyRetainsAnExactIdempotentHoldAfterReopen() = withDatabase { f ->
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, original, null),
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
                    error("Lost hold response")
                }
            }
        assertIs<AppResult.Failed>(f.outbox(store).hold(f.access, original))
        f.reopen()
        assertEquals(AppResult.Success(true), f.outbox().hold(f.access, original))
        assertTrue(f.pending().submissions.isEmpty())
    }

    @Test
    fun activeAndHeldObservationsRemainCoherentDuringAnUnrelatedEditorWrite() = withDatabase { f ->
        val a = f.submission()
        val b = f.submission(1)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, a, null),
        )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, b, a.nextDraft),
        )
        assertEquals(AppResult.Success(true), f.outbox().hold(f.access, a))
        var changed = false
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> =
                    f.database.replicas.observeQueries(scope, partition, queries).map { page ->
                        assertEquals(2, queries.size)
                        assertTrue(queries.all { it.limit == 129 })
                        if (!changed) {
                            changed = true
                            assertEquals(
                                AppResult.Success(true),
                                f.drafts()
                                    .replace(
                                        f.access,
                                        b.nextDraft,
                                        b.nextDraft.copy(
                                            revision = historyId(700),
                                            text = "New text",
                                        ),
                                    ),
                            )
                        }
                        page
                    }
            }
        assertEquals(
            AppResult.Success(
                listOf(
                    CollaborationComposerOutboxEntry(a, true),
                    CollaborationComposerOutboxEntry(b, false),
                )
            ),
            f.outbox(store).observe(f.access).first(),
        )
        assertTrue(changed)
    }

    @Test
    fun aChangedIndexCannotTurnAnAuthenticatedHeldEntryBackIntoPending() = withDatabase { f ->
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, original, null),
        )
        assertEquals(AppResult.Success(true), f.outbox().hold(f.access, original))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> =
                    f.database.replicas.observeDocument(scope, partition, id).map { page ->
                        page.copy(
                            documents =
                                page.documents.map { it.copy(tags = setOf(COMPOSER_OUTBOX_TAG)) }
                        )
                    }
            }
        assertIs<AppResult.Failed>(f.outbox(store).get(f.access, original.id))
        assertIs<AppResult.Failed>(f.outbox(store).hold(f.access, original))
    }

    @Test
    fun heldEntriesStillCountTowardCapacityWithoutLosingTheNextEditor() = withDatabase { f ->
        var previous: CollaborationDraft? = null
        repeat(128) { index ->
            val original = f.submission(index)
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, original, previous),
            )
            assertEquals(AppResult.Success(true), f.outbox().hold(f.access, original))
            previous = original.nextDraft
        }
        assertTrue(f.pending().submissions.isEmpty())
        val next = f.submission(128)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CAPACITY_EXCEEDED),
            f.outbox().handoff(f.access, next, previous),
        )
        assertEquals(AppResult.Success(previous), f.drafts().get(f.access, f.conversation))
        assertEquals(
            128,
            assertIs<AppResult.Success<List<CollaborationComposerOutboxEntry>>>(
                    f.outbox().observe(f.access).first()
                )
                .value
                .size,
        )
    }

    @Test
    fun composerCursorSurvivesReopenAndCompetingAdvancementPreservesTheWinner() =
        withDatabase { f ->
            val original = f.submission()
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, original, null),
            )
            fun cursor() =
                EncryptedCollaborationComposerRecoveryCursorRepository(
                    f.database.replicas,
                    f.cipher,
                    f.keys,
                )
            assertEquals(AppResult.Success(null), cursor().current(f.access))
            val first =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                            cursor().advance(f.access, null, original.id)
                        )
                        .value
                )
            assertEquals(original.id, first.afterSubmissionId)
            assertEquals(AppResult.Success(null), cursor().advance(f.access, null, historyId(900)))
            f.reopen()
            assertEquals(AppResult.Success(first), cursor().current(f.access))
            val next =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationComposerRecoveryCursor?>>(
                            cursor().advance(f.access, first, historyId(900))
                        )
                        .value
                )
            assertNotEquals(first.generation, next.generation)
            assertEquals(AppResult.Success(next), cursor().current(f.access))
            assertEquals(
                AppResult.Success(null),
                EncryptedCollaborationDraftRecoveryCursorRepository(
                        f.database.replicas,
                        f.cipher,
                        f.keys,
                    )
                    .current(f.access),
            )
        }

    @Test
    fun cursorCannotReplaceALostContentKeyOrMaskAnotherCompanysHistory() = withDatabase { f ->
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(f.access, original, null),
        )
        f.keys.write(null)
        val cursor =
            EncryptedCollaborationComposerRecoveryCursorRepository(
                f.database.replicas,
                f.cipher,
                f.keys,
            )
        assertIs<AppResult.Failed>(
            cursor.advance(f.access.copy(companyId = historyId(999)), null, original.id)
        )
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
