package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.notifications.NOTIFICATION_REPLY_PARTITION
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** Actual content cipher, authenticated membership and account-scoped SQLite/IndexedDB storage. */
abstract class CollaborationNotificationReplyStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun exactInputAndTokenReopenWithoutPlaintextMetadataOrHistoryMutation() = withDatabase { f ->
        val history = f.raw()
        val candidate = f.candidate()
        val retained = f.retain(candidate)
        val raw =
            f.database.replicas
                .observe(recoveryScope(f.inputAccess()), NOTIFICATION_REPLY_PARTITION, limit = 200)
                .first()
        val encoded = listOf(raw.checkpoint.toString()) + raw.documents.map { it.toString() }
        for (sensitive in
            listOf(
                candidate.text,
                candidate.token.encoded,
                candidate.parent.nativeSessionId,
                candidate.action.operationId,
                f.conversation,
                f.room,
            )) {
            assertTrue(encoded.none { sensitive in it })
            assertFalse(candidate.toString().contains(sensitive))
            assertFalse(retained.toString().contains(sensitive))
        }
        f.reopen()
        assertEquals(retained, expect(f.inputs().get(f.inputAccess(), retained.id)))
        assertEquals(candidate, expect(f.inputs().page(f.inputAccess())).inputs.single().candidate)
        assertEquals(history, f.raw())
    }

    @Test
    fun duplicateAndResealedTokensRecoverTheOriginalWhileChangedTextKeepsBothInputs() =
        withDatabase { f ->
            val candidate = f.candidate()
            val first = f.retain(candidate)
            assertEquals(first, f.retain(candidate))
            val resealed = f.candidate()
            assertNotEquals(candidate.token, resealed.token)
            assertEquals(first, f.retain(resealed))
            val changed = f.retain(candidate.copy(text = "Fictional second reply"))
            assertNotEquals(first.id, changed.id)
            assertEquals(first.candidate.action.operationId, changed.candidate.action.operationId)
            val all = expect(f.inputs().page(f.inputAccess(), pendingOnly = false)).inputs
            assertEquals(listOf(first, changed), all)
            assertEquals(candidate.token, all.first().candidate.token)
        }

    @Test
    fun reviewedInputsRemainRetainedAndPagingUsesBoundedLocalCreationOrder() = withDatabase { f ->
        val stored = (1..5).map { f.retain(f.candidate(it)) }
        assertTrue(
            expect(
                f.inputs()
                    .hold(
                        f.inputAccess(),
                        stored[1],
                        CollaborationNotificationReplyReview.MessageChanged,
                    )
            )
        )
        assertTrue(
            expect(
                f.inputs()
                    .hold(
                        f.inputAccess(),
                        stored[3],
                        CollaborationNotificationReplyReview.SessionChanged,
                    )
            )
        )
        val first = expect(f.inputs().page(f.inputAccess(), limit = 2))
        assertEquals(listOf(stored[0].id, stored[2].id), first.inputs.map { it.id })
        assertEquals(stored[2].sequence, first.nextAfter)
        val next = expect(f.inputs().page(f.inputAccess(), first.nextAfter, 2))
        assertEquals(listOf(stored[4]), next.inputs)
        assertNull(next.nextAfter)
        assertEquals(5, expect(f.inputs().page(f.inputAccess(), pendingOnly = false)).inputs.size)
        val reviewed = f.retain(stored[1].candidate)
        assertEquals(CollaborationNotificationReplyReview.MessageChanged, reviewed.review)
    }

    @Test
    fun exactVersionRemovalCannotEraseReviewedOrLaterRecreatedInput() = withDatabase { f ->
        val first = f.retain(f.candidate())
        assertTrue(
            expect(
                f.inputs()
                    .hold(
                        f.inputAccess(),
                        first,
                        CollaborationNotificationReplyReview.ContextUnavailable,
                    )
            )
        )
        val reviewed = requireNotNull(expect(f.inputs().get(f.inputAccess(), first.id)))
        assertFalse(expect(f.inputs().remove(f.inputAccess(), first)))
        assertFalse(
            expect(
                f.inputs()
                    .hold(
                        f.inputAccess(),
                        first,
                        CollaborationNotificationReplyReview.MessageChanged,
                    )
            )
        )
        assertTrue(expect(f.inputs().remove(f.inputAccess(), reviewed)))
        assertTrue(expect(f.inputs().remove(f.inputAccess(), reviewed)))
        assertNull(expect(f.inputs().get(f.inputAccess(), first.id)))
        val recreated = f.retain(first.candidate)
        assertEquals(first.id, recreated.id)
        assertTrue(recreated.sequence > reviewed.sequence)
        assertFalse(expect(f.inputs().remove(f.inputAccess(), reviewed)))
        assertEquals(recreated, expect(f.inputs().get(f.inputAccess(), recreated.id)))
    }

    @Test
    fun capacityNeverEvictsTypedInputOrOriginalHistory() = withDatabase { f ->
        val stored = (1..64).map { f.retain(f.candidate(it)) }
        assertEquals(stored.first(), f.retain(stored.first().candidate))
        assertTrue(
            expect(
                f.inputs()
                    .hold(
                        f.inputAccess(),
                        stored.first(),
                        CollaborationNotificationReplyReview.MessageChanged,
                    )
            )
        )
        val next = f.candidate(65)
        assertEquals(
            CollaborationNotificationReplyRetention.CapacityExceeded,
            expect(f.inputs().retain(next)),
        )
        val retained = mutableListOf<CollaborationNotificationReplyInput>()
        var after: Long? = null
        do {
            val page = expect(f.inputs().page(f.inputAccess(), after, 20, false))
            retained += page.inputs
            after = page.nextAfter
        } while (after != null)
        assertEquals(stored.map { it.id }, retained.map { it.id })
        assertTrue(expect(f.inputs().remove(f.inputAccess(), retained.first())))
        assertIs<CollaborationNotificationReplyRetention.Retained>(expect(f.inputs().retain(next)))
        assertNotNull(f.lookup())
    }

    @Test
    fun sameAccountReauthenticationRetainsOriginalFamilyButReplacementAccountCannotReadIt() =
        withDatabase { f ->
            val first = f.retain(f.candidate())
            val renewed = f.access.copy(sessionId = "renewed-login")
            f.reopen(renewed)
            val renewedAccess =
                CollaborationRecoveryAccess(renewed.sessionId, renewed.audience, renewed.accountId)
            assertEquals(first, expect(f.inputs().get(renewedAccess, first.id)))
            assertEquals(
                first.candidate.parent,
                expect(f.inputs().page(renewedAccess)).inputs.single().candidate.parent,
            )
            val other = renewed.copy(accountId = historyId(9899))
            f.activate(other)
            // Raw replica reads return absence for inactive ownership; the use case guards
            // current session separately. No old account input may be exposed here.
            assertNull(expect(f.inputs().get(renewedAccess, first.id)))
            assertNull(
                expect(
                    f.inputs()
                        .get(
                            CollaborationRecoveryAccess(
                                other.sessionId,
                                other.audience,
                                other.accountId,
                            ),
                            first.id,
                        )
                )
            )
            f.activate(renewed)
            assertEquals(first, expect(f.inputs().get(renewedAccess, first.id)))
        }

    @Test
    fun missingOrWrongKeyCannotRegenerateOverRetainedInputs() = withDatabase { f ->
        val first = f.retain(f.candidate())
        val key = f.keys.read()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.inputs().get(f.inputAccess(), first.id))
        assertIs<AppResult.Failed>(f.inputs().retain(first.candidate))
        assertNull(f.keys.read())
        f.keys.write(Base64.encode(ByteArray(64) { 8 }))
        assertIs<AppResult.Failed>(f.inputs().page(f.inputAccess()))
        f.keys.write(key)
        assertEquals(first, expect(f.inputs().get(f.inputAccess(), first.id)))
        assertNotNull(f.lookup())
    }

    @Test
    fun changedMissingOrRelabeledReferencedDocumentsAreRejected() = withDatabase { f ->
        val first = f.retain(f.candidate())
        for (mode in listOf("missing", "payload", "id", "revision", "sort", "tags")) {
            val actual = f.database.replicas
            val corrupt =
                object : ReplicaStore by actual {
                    override suspend fun document(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): ReplicaDocument? {
                        val doc = requireNotNull(actual.document(scope, partition, id))
                        return when (mode) {
                            "missing" -> null
                            "payload" -> doc.copy(payload = "AAAA")
                            "id" -> doc.copy(id = "n-" + "0".repeat(64))
                            "revision" -> doc.copy(version = doc.version + 1)
                            "sort" -> doc.copy(sortKey = "changed")
                            else -> doc.copy(tags = setOf("injected"))
                        }
                    }
                }
            assertIs<AppResult.Failed>(f.inputs(corrupt).get(f.inputAccess(), first.id))
        }
        assertEquals(first, expect(f.inputs().get(f.inputAccess(), first.id)))
    }

    @Test
    fun atomicCommitWithLostReplyRecoversOneExactInputWithoutHiddenRetry() = withDatabase { f ->
        f.retain(f.candidate())
        val next = f.candidate(2)
        val actual = f.database.replicas
        var commits = 0
        val uncertain =
            object : ReplicaStore by actual {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    commits++
                    check(actual.commit(handle, expectedRevision, checkpoint, documents, publish))
                    error("Simulated lost storage reply")
                }
            }
        assertIs<AppResult.Failed>(f.inputs(uncertain).retain(next))
        assertEquals(1, commits)
        f.reopen()
        val recovered = f.retain(next)
        assertEquals(next, recovered.candidate)
        assertEquals(2, expect(f.inputs().page(f.inputAccess())).inputs.size)
    }

    @Test
    fun cancellationAfterCommitJoinsTheWriterAndKeepsItsRecoverableInput() = withDatabase { f ->
        f.retain(f.candidate())
        val next = f.candidate(2)
        val actual = f.database.replicas
        val committed = CompletableDeferred<Unit>()
        var stopped = false
        val delayed =
            object : ReplicaStore by actual {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                ): Boolean {
                    check(actual.commit(handle, expectedRevision, checkpoint, documents, publish))
                    try {
                        committed.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        var acknowledged = false
        val job =
            CoroutineScope(currentCoroutineContext()).launch {
                f.inputs(delayed).retain(next)
                acknowledged = true
            }
        committed.await()
        job.cancelAndJoin()
        assertTrue(stopped)
        assertFalse(acknowledged)
        f.reopen()
        assertEquals(next, f.retain(next).candidate)
        assertEquals(2, expect(f.inputs().page(f.inputAccess())).inputs.size)
    }

    @Test
    fun accountReplacementAtCommitCannotPublishOldInputIntoTheReplacementAccount() =
        withDatabase { f ->
            f.retain(f.candidate())
            val next = f.candidate(2)
            val actual = f.database.replicas
            val other = f.access.copy(sessionId = "other-login", accountId = historyId(9899))
            val replaced =
                object : ReplicaStore by actual {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                    ): Boolean {
                        f.activate(other)
                        return actual.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                        )
                    }
                }
            val result = f.inputs(replaced).retain(next)
            assertFalse(
                result is AppResult.Success &&
                    result.value is CollaborationNotificationReplyRetention.Retained
            )
            assertTrue(
                expect(
                        f.inputs()
                            .page(
                                CollaborationRecoveryAccess(
                                    other.sessionId,
                                    other.audience,
                                    other.accountId,
                                )
                            )
                    )
                    .inputs
                    .isEmpty()
            )
            f.activate()
            assertEquals(1, expect(f.inputs().page(f.inputAccess())).inputs.size)
        }

    @Test
    fun invalidTextMetadataAndPageBoundsRejectWithoutCreatingRecords() = withDatabase { f ->
        val candidate = f.candidate()
        for (invalid in
            listOf(
                candidate.copy(text = " "),
                candidate.copy(text = "x".repeat(32_769)),
                candidate.copy(text = "\uD800"),
                candidate.copy(action = candidate.action.copy(operationId = "bad")),
                candidate.copy(parent = candidate.parent.copy(nativeSessionId = "bad")),
                candidate.copy(token = CollaborationNotificationActionToken("v2.invalid")),
            )) assertIs<AppResult.Failed>(f.inputs().retain(invalid))
        assertIs<AppResult.Failed>(f.inputs().page(f.inputAccess(), limit = 0))
        assertIs<AppResult.Failed>(f.inputs().page(f.inputAccess(), limit = 21))
        assertIs<AppResult.Failed>(f.inputs().page(f.inputAccess(), after = -1))
        assertTrue(expect(f.inputs().page(f.inputAccess())).inputs.isEmpty())
    }

    @Test
    fun maximumMultibyteReplyFitsTheEncryptedDocumentBoundAndReopens() = withDatabase { f ->
        val candidate = f.candidate().copy(text = "🙂".repeat(8192))
        val retained = f.retain(candidate)
        f.reopen()
        assertEquals(retained, expect(f.inputs().get(f.inputAccess(), retained.id)))
    }

    private fun CollaborationHistoryFixture.inputAccess() =
        CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)

    private fun CollaborationHistoryFixture.inputs(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationNotificationReplyRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.candidate(
        index: Int = 1
    ): CollaborationNotificationReplyCandidate {
        val parent =
            CollaborationNativeParent(
                access.sessionId,
                access.audience,
                access.accountId,
                historyId(9800),
            )
        val target =
            CollaborationNotificationActionTarget(
                access.companyId,
                conversation,
                room,
                event().eventId,
                historyId(9801),
                historyId(9802),
            )
        val action = CollaborationNotificationAction.Reply(historyId(9803 + index), target)
        val token =
            expect(
                EncryptedCollaborationNotificationActionRepository(database.replicas, cipher, keys)
                    .seal(parent, action)
            )
        return CollaborationNotificationReplyCandidate(
            parent,
            action,
            token,
            "Fictional retained reply $index 🙂",
        )
    }

    private suspend fun CollaborationHistoryFixture.retain(
        candidate: CollaborationNotificationReplyCandidate
    ) =
        assertIs<CollaborationNotificationReplyRetention.Retained>(
                expect(inputs().retain(candidate))
            )
            .input

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            f.merge(f.event())
            block(f)
        } finally {
            f.close()
        }
    }
}
