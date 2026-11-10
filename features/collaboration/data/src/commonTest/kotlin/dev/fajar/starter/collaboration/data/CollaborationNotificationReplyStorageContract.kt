package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.notifications.NOTIFICATION_REPLY_PARTITION
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

/** Actual content cipher, authenticated membership and account-scoped SQLite/IndexedDB storage. */
abstract class CollaborationNotificationReplyStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun oldIssuerUsesCurrentStorageOwnerAndPreservesItsDescriptorAcrossReopen() =
        withDatabase { f ->
            val candidate = f.candidate()
            val renewed = f.access.copy(sessionId = "replacement-login")
            val access = f.inputAccess().copy(sessionId = renewed.sessionId)
            f.reopen(renewed)
            assertEquals(
                CollaborationNotificationReplyRetention.Contended,
                expect(f.inputs().retain(f.inputAccess(), candidate)),
            )
            val input =
                assertIs<CollaborationNotificationReplyRetention.Retained>(
                        expect(f.inputs().retain(access, candidate))
                    )
                    .input
            assertEquals(candidate, input.candidate)
            for (foreign in
                listOf(
                    access.copy(accountId = historyId(9970)),
                    access.copy(audience = "https://other.example.test/"),
                )) {
                assertIs<AppResult.Failed>(f.inputs().retain(foreign, candidate))
            }
            f.reopen(renewed)
            assertEquals(input, expect(f.inputs().get(access, input.id)))
            assertEquals(
                input,
                assertIs<CollaborationNotificationReplyRetention.Retained>(
                        expect(f.inputs().retain(access, candidate))
                    )
                    .input,
            )
            assertEquals(listOf(input), expect(f.inputs().page(access)).inputs)
        }

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
            expect(f.inputs().retain(f.inputAccess(), next)),
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
        assertIs<CollaborationNotificationReplyRetention.Retained>(
            expect(f.inputs().retain(f.inputAccess(), next))
        )
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
        assertIs<AppResult.Failed>(f.inputs().retain(f.inputAccess(), first.candidate))
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
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    commits++
                    check(
                        actual.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    )
                    error("Simulated lost storage reply")
                }
            }
        assertIs<AppResult.Failed>(f.inputs(uncertain).retain(f.inputAccess(), next))
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
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    check(
                        actual.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    )
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
                f.inputs(delayed).retain(f.inputAccess(), next)
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
                        wake: ReplicaWakeRequest?,
                    ): Boolean {
                        f.activate(other)
                        return actual.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    }
                }
            val result = f.inputs(replaced).retain(f.inputAccess(), next)
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
                candidate.copy(token = CollaborationNotificationActionToken("v3.invalid")),
            )) assertIs<AppResult.Failed>(f.inputs().retain(f.inputAccess(), invalid))
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

    @Test
    fun observationSignalsRetentionHoldAndRemovalWithoutReturningPrivateContent() =
        withDatabase { f ->
            coroutineScope {
                val signals =
                    kotlinx.coroutines.channels.Channel<AppResult<Unit>>(
                        kotlinx.coroutines.channels.Channel.UNLIMITED
                    )
                val repo = f.inputs()
                val job = launch {
                    repo.observeChanges(f.inputAccess()).collect { signals.send(it) }
                }
                try {
                    assertEquals(AppResult.Success(Unit), signals.receive())
                    val input = f.retain(f.candidate())
                    assertEquals(AppResult.Success(Unit), signals.receive())
                    assertEquals(
                        AppResult.Success(true),
                        repo.hold(
                            f.inputAccess(),
                            input,
                            CollaborationNotificationReplyReview.SessionChanged,
                        ),
                    )
                    assertEquals(AppResult.Success(Unit), signals.receive())
                    val held = assertNotNull(expect(repo.get(f.inputAccess(), input.id)))
                    assertEquals(AppResult.Success(true), repo.remove(f.inputAccess(), held))
                    assertEquals(AppResult.Success(Unit), signals.receive())
                } finally {
                    job.cancelAndJoin()
                    signals.cancel()
                }
            }
        }

    @Test
    fun editedDraftAndConfirmedResolutionSurviveReopenAndDuplicateCallbacks() = withDatabase { f ->
        val original = f.retain(f.candidate())
        val access = f.inputAccess()
        val repo = f.inputs()
        assertTrue(
            expect(repo.hold(access, original, CollaborationNotificationReplyReview.SessionChanged))
        )
        val held = assertNotNull(expect(repo.get(access, original.id)))
        assertTrue(expect(repo.saveDraft(access, held, "Private edited working copy")))
        f.reopen()
        val drafted = assertNotNull(expect(f.inputs().get(access, original.id)))
        assertEquals("Private edited working copy", drafted.draft)
        assertEquals(drafted, f.retain(original.candidate))
        val resolution =
            CollaborationNotificationReplyResolution(
                original.candidate.parent,
                historyId(9910),
                Instant.fromEpochMilliseconds(1_793_491_200_000),
                "Explicit edited send",
            )
        assertTrue(expect(f.inputs().confirm(access, drafted, resolution)))
        f.reopen()
        val confirmed = assertNotNull(expect(f.inputs().get(access, original.id)))
        assertEquals(resolution, confirmed.resolution)
        assertNull(confirmed.review)
        assertNull(confirmed.draft)
        assertEquals(confirmed, f.retain(original.candidate))
        assertEquals(listOf(confirmed), expect(f.inputs().page(access)).inputs)
        val raw =
            f.database.replicas
                .observe(recoveryScope(access), NOTIFICATION_REPLY_PARTITION, limit = 200)
                .first()
        assertTrue(
            raw.documents.none {
                "Explicit edited send" in it.toString() ||
                    "Private edited working copy" in it.toString()
            }
        )
        assertFalse(confirmed.toString().contains("Explicit edited send"))
    }

    @Test
    fun staleDraftCannotOverwriteConfirmationAndReapprovalOnlyChangesParent() = withDatabase { f ->
        val repo = f.inputs()
        val access = f.inputAccess()
        val original = f.retain(f.candidate())
        assertTrue(
            expect(repo.hold(access, original, CollaborationNotificationReplyReview.SessionChanged))
        )
        val held = assertNotNull(expect(repo.get(access, original.id)))
        assertTrue(expect(repo.saveDraft(access, held, "")))
        val empty = assertNotNull(expect(repo.get(access, original.id)))
        assertEquals("", empty.draft)
        assertFalse(expect(repo.saveDraft(access, held, "Stale editor")))
        val resolution =
            CollaborationNotificationReplyResolution(
                original.candidate.parent,
                historyId(9911),
                Instant.fromEpochMilliseconds(1_793_491_200_001),
                "Approved",
            )
        assertTrue(expect(repo.confirm(access, empty, resolution)))
        assertFalse(expect(repo.saveDraft(access, empty, "Late edit")))
        val confirmed = assertNotNull(expect(repo.get(access, original.id)))
        assertIs<AppResult.Failed>(
            repo.confirm(access, confirmed, resolution.copy(text = "Different"))
        )
        assertIs<AppResult.Failed>(
            repo.confirm(access, confirmed, resolution.copy(operationId = historyId(9912)))
        )
        val approved =
            resolution.copy(parent = resolution.parent.copy(nativeSessionId = historyId(9913)))
        assertTrue(expect(repo.confirm(access, confirmed, approved)))
        assertEquals(approved, assertNotNull(expect(repo.get(access, original.id))).resolution)
    }

    @Test
    fun largestValidBodiesStayWithinExistingReplicaBoundsWithoutTruncation() = withDatabase { f ->
        val candidate = f.candidate().copy(text = "🙂".repeat(8192))
        val original = f.retain(candidate)
        val repo = f.inputs()
        val access = f.inputAccess()
        assertTrue(
            expect(repo.hold(access, original, CollaborationNotificationReplyReview.SessionChanged))
        )
        val held = assertNotNull(expect(repo.get(access, original.id)))
        val draft = "\n".repeat(16_384)
        assertTrue(expect(repo.saveDraft(access, held, draft)))
        f.reopen()
        val drafted = assertNotNull(expect(f.inputs().get(access, original.id)))
        assertEquals(draft, drafted.draft)
        assertEquals(candidate, drafted.candidate)
        val resolution =
            CollaborationNotificationReplyResolution(
                candidate.parent,
                historyId(9914),
                Instant.fromEpochMilliseconds(1_793_491_200_001),
                "é".repeat(16_384),
            )
        assertTrue(expect(f.inputs().confirm(access, drafted, resolution)))
        f.reopen()
        assertEquals(
            resolution,
            assertNotNull(expect(f.inputs().get(access, original.id))).resolution,
        )
        val raw =
            f.database.replicas
                .observe(recoveryScope(access), NOTIFICATION_REPLY_PARTITION, limit = 200)
                .first()
        assertTrue(raw.documents.all { requireNotNull(it.payload).length <= 131_072 })
    }

    @Test
    fun schemaOneCiphertextReopensAndCanBeEditedWithoutChangingInputIdentity() = withDatabase { f ->
        val legacyCipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    val objectValue =
                        Json.parseToJsonElement(plaintext.decodeToString()) as? JsonObject
                    if (objectValue?.containsKey("action") != true)
                        return f.cipher.encrypt(key, associatedData, plaintext)
                    val fields =
                        objectValue.toMutableMap().apply {
                            this["schema"] = JsonPrimitive(1)
                            remove("draft")
                            remove("resolution")
                        }
                    val legacy = JsonObject(fields).toString().encodeToByteArray()
                    return try {
                        f.cipher.encrypt(key, associatedData, legacy)
                    } finally {
                        legacy.fill(0)
                    }
                }
            }
        val candidate = f.candidate()
        val old =
            EncryptedCollaborationNotificationReplyRepository(
                f.database.replicas,
                legacyCipher,
                f.keys,
            )
        val original =
            assertIs<CollaborationNotificationReplyRetention.Retained>(
                    expect(old.retain(f.inputAccess(), candidate))
                )
                .input
        f.reopen()
        val repo = f.inputs()
        val access = f.inputAccess()
        assertEquals(original, expect(repo.get(access, original.id)))
        assertTrue(
            expect(repo.hold(access, original, CollaborationNotificationReplyReview.SessionChanged))
        )
        val held = assertNotNull(expect(repo.get(access, original.id)))
        assertTrue(expect(repo.saveDraft(access, held, "Edited legacy reply")))
        assertEquals(original.id, assertNotNull(expect(repo.get(access, original.id))).id)
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
                expect(inputs().retain(inputAccess(), candidate))
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
