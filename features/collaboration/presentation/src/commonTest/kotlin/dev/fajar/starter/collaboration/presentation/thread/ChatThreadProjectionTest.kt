package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

class ChatThreadProjectionTest {
    @Test
    fun capturedOriginalKeepsOneStableBubbleThroughOutboxCommandAndArchivedCompletion() {
        val gesture =
            CollaborationComposerSubmission(
                CollaborationDraft(conversation, intent.id, intent.text, intent.createdAt),
                chatId(99),
                intent.createdAt,
            )
        val captured = ChatComposerPendingSubmission(gesture)
        fun projected(
            progress: List<CollaborationComposerMessageProgress>,
            commands: List<CollaborationMessageRecord> = emptyList(),
            history: List<CollaborationHistoryMessage> = emptyList(),
        ) =
            assertIs<AppResult.Success<List<ChatMessageRow>>>(
                    chatThreadRows(
                        owner,
                        conversation,
                        self,
                        device,
                        history,
                        commands,
                        emptyList(),
                        historyRoomId = device.roomId,
                        composer = progress,
                        provisional = listOf(captured),
                    )
                )
                .value
        val immediate = projected(emptyList()).single()
        assertEquals(CollaborationMessageDelivery.PENDING, immediate.delivery)
        assertEquals(gesture, immediate.composerSubmission)
        assertEquals(
            immediate,
            projected(listOf(CollaborationComposerMessageProgress(gesture, null, retained = true)))
                .single(),
        )
        val queued = CollaborationMessageRecord(intent, 0, CollaborationMessageState.Queued)
        assertEquals(
            immediate.key,
            projected(listOf(CollaborationComposerMessageProgress(gesture, queued)), listOf(queued))
                .single()
                .key,
        )
        val sent =
            staged.copy(
                state = CollaborationMessageState.Sent(device, "transaction", "\$original", origin)
            )
        val accepted =
            projected(listOf(CollaborationComposerMessageProgress(gesture, sent))).single()
        assertEquals(immediate.key, accepted.key)
        assertEquals(CollaborationMessageDelivery.SENT, accepted.delivery)
        assertEquals(
            1,
            projected(
                    listOf(CollaborationComposerMessageProgress(gesture, sent)),
                    history = listOf(original),
                )
                .size,
        )
    }

    @Test
    fun cancellationAlreadyArchivedBeforeObservationCannotLeaveAProvisionalGhost() {
        val gesture =
            CollaborationComposerSubmission(
                CollaborationDraft(conversation, intent.id, intent.text, intent.createdAt),
                chatId(99),
                intent.createdAt,
            )
        val cancelled = CollaborationMessageRecord(intent, 0, CollaborationMessageState.Cancelled)
        val progress =
            listOf(CollaborationComposerMessageProgress(gesture, cancelled, retained = true))
        val rows =
            assertIs<AppResult.Success<List<ChatMessageRow>>>(
                    chatThreadRows(
                        owner,
                        conversation,
                        self,
                        null,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        composer = progress,
                        provisional = listOf(ChatComposerPendingSubmission(gesture, durable = true)),
                    )
                )
                .value
        assertTrue(rows.isEmpty())
        assertEquals(
            setOf(gesture),
            progress.visibleComposerOriginals(emptyList(), emptyList(), null),
        )
        assertTrue(
            progress
                .visibleComposerOriginals(
                    listOf(cancelled.copy(state = CollaborationMessageState.Queued)),
                    emptyList(),
                    null,
                )
                .isEmpty()
        )
    }

    @Test
    fun heldOriginalStaysVisibleAndAConflictingCommandCannotReplaceIt() {
        val gesture =
            CollaborationComposerSubmission(
                CollaborationDraft(conversation, intent.id, intent.text, intent.createdAt),
                chatId(99),
                intent.createdAt,
            )
        val held =
            CollaborationComposerMessageProgress(
                gesture,
                null,
                retained = true,
                reviewRequired = true,
            )
        val rows =
            assertIs<AppResult.Success<List<ChatMessageRow>>>(
                    chatThreadRows(
                        owner,
                        conversation,
                        self,
                        null,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        composer = listOf(held),
                    )
                )
                .value
        assertEquals(CollaborationMessageDelivery.FAILED, rows.single().delivery)
        assertEquals(intent.text, rows.single().text)
        assertIs<AppResult.Failed>(
            chatThreadRows(
                owner,
                conversation,
                self,
                null,
                emptyList(),
                emptyList(),
                emptyList(),
                composer =
                    listOf(
                        held.copy(
                            record = staged.copy(intent = intent.copy(text = "Wrong original"))
                        )
                    ),
            )
        )
    }

    @Test
    fun sdkLocalEchoWithServerIdAndNoJsonKeepsOnePendingRowUntilOriginalEvidenceArrives() {
        val localSent = native.copy(transactionId = null, logicalId = null, localEcho = true)
        for (record in
            listOf(
                staged.copy(state = CollaborationMessageState.Staging(device, emptySet(), origin)),
                staged,
            )) {
            val before = rows(pending = listOf(record)).single()
            val sending = rows(pending = listOf(record), timeline = listOf(localSent)).single()
            assertEquals(before.key, sending.key)
            assertEquals(intent.text, sending.text)
            assertEquals(CollaborationMessageDelivery.PENDING, sending.delivery)
            assertNull(sending.eventId)
            assertFalse(sending.canCancel)
        }
        val received = rows(pending = listOf(staged), timeline = listOf(native)).single()
        assertEquals("outgoing:${intent.id}", received.key)
        assertEquals(CollaborationMessageDelivery.SENT, received.delivery)
        assertEquals("\$original", received.eventId)
        val retained = rows(history = listOf(original), timeline = listOf(localSent)).single()
        assertEquals(received.key, retained.key)
        assertEquals(CollaborationMessageDelivery.SENT, retained.delivery)
    }

    @Test
    fun unmatchedSdkLocalEchoCannotReplaceMissingDurableIntentOrHideIncomingMessages() {
        val local = native.copy(transactionId = null, logicalId = null, localEcho = true)
        assertTrue(rows(timeline = listOf(local)).isEmpty())
        val incoming =
            native.copy(
                eventId = "\$incoming",
                transactionId = null,
                logicalId = null,
                sender = "@peer:example.test",
                own = false,
                body = "Incoming text",
            )
        val projected = rows(pending = listOf(staged), timeline = listOf(local, incoming))
        assertEquals(2, projected.size)
        assertEquals("Incoming text", projected.single { it.eventId == "\$incoming" }.text)
        assertEquals(
            CollaborationMessageDelivery.PENDING,
            projected.single { it.localId == intent.id }.delivery,
        )
    }

    @Test
    fun sequentialReceiptsKeepPendingMessagesAfterTheConfirmedPrefixDespiteClockSkew() {
        val bodies = listOf("AAA", "BBB", "CCC", "DDD")
        for (clientBase in listOf(1_000L, 20_000L)) {
            val records =
                bodies.mapIndexed { index, body ->
                    staged.copy(
                        intent =
                            intent.copy(
                                id = chatId(100 + index),
                                text = body,
                                createdAt = Instant.fromEpochMilliseconds(clientBase + index),
                            ),
                        state =
                            CollaborationMessageState.Staged(device, "transaction-$index", origin),
                    )
                }
            for (confirmedCount in 0..4) {
                val confirmed =
                    records.take(confirmedCount).mapIndexed { index, record ->
                        original.copy(
                            logicalId = record.intent.id,
                            primaryEventId = "\$accepted-$index",
                            eventIds = listOf("\$accepted-$index"),
                            timestamp = 10_000L + index,
                            text = record.intent.text,
                        )
                    }
                // Earlier commands may already be retired when their successors are still pending.
                val projected = rows(history = confirmed, pending = records.drop(confirmedCount))
                assertEquals(bodies.reversed(), projected.map { it.text })
                assertEquals(
                    records.reversed().map { "outgoing:${it.intent.id}" },
                    projected.map { it.key },
                )
                for (record in records.drop(confirmedCount)) {
                    val row = projected.single { it.localId == record.intent.id }
                    assertEquals(CollaborationMessageDelivery.PENDING, row.delivery)
                    assertEquals(record.intent.createdAt.toEpochMilliseconds(), row.timestamp)
                }
                for (message in confirmed) {
                    val row = projected.single { it.eventId == message.primaryEventId }
                    assertEquals(CollaborationMessageDelivery.SENT, row.delivery)
                    assertEquals(message.timestamp, row.timestamp)
                }
            }
        }
    }

    @Test
    fun forwardIndicatorFollowsPendingLiveAndStoredContentButNotDeletedRows() {
        val forwarded = intent.copy(forwarded = true)
        assertTrue(
            rows(
                    pending =
                        listOf(
                            CollaborationMessageRecord(
                                forwarded,
                                0,
                                CollaborationMessageState.Queued,
                            )
                        )
                )
                .single()
                .forwarded
        )
        assertTrue(rows(timeline = listOf(native.copy(forwarded = true))).single().forwarded)
        assertTrue(rows(history = listOf(original.copy(forwarded = true))).single().forwarded)
        assertTrue(
            rows(history = listOf(original), pending = listOf(staged.copy(intent = forwarded)))
                .single()
                .forwarded
        )
        assertFalse(
            rows(history = listOf(original.copy(forwarded = true, redacted = true, text = null)))
                .single()
                .forwarded
        )
        assertFalse(
            rows(
                    timeline =
                        listOf(
                            native.copy(
                                forwarded = true,
                                kind = CollaborationMessageKind.REDACTED,
                                body = null,
                            )
                        )
                )
                .single()
                .forwarded
        )
    }

    @Test
    fun cancellationIsAvailableOnlyForAnUnmatchedOriginalQueuedRow() {
        val queued = CollaborationMessageRecord(intent, 0, CollaborationMessageState.Queued)
        assertTrue(rows(pending = listOf(queued)).single().canCancel)
        assertFalse(rows(pending = listOf(queued.copy(revision = 1))).single().canCancel)
        assertFalse(rows(pending = listOf(staged)).single().canCancel)
        assertFalse(
            rows(
                    pending =
                        listOf(
                            staged.copy(
                                state =
                                    CollaborationMessageState.Staging(device, emptySet(), origin)
                            )
                        )
                )
                .single()
                .canCancel
        )
        assertFalse(rows(history = listOf(original), pending = listOf(staged)).single().canCancel)
        assertFalse(rows(timeline = listOf(native)).single().canCancel)
        assertIs<AppResult.Failed>(
            chatThreadRows(
                owner,
                conversation,
                self,
                device,
                emptyList(),
                listOf(queued.copy(state = CollaborationMessageState.Cancelled)),
                emptyList(),
            )
        )
    }

    private val sender = "@self:example.test"
    private val self = CollaborationSelfIdentity(sender)
    private val owner =
        CollaborationMessageOwner("https://hris.example.test/", chatId(1), chatId(2))
    private val conversation = chatId(3)
    private val device =
        CollaborationMessageDevice(chatId(4), chatId(5), chatId(6), "!room:example.test")
    private val origin = CollaborationMessageOrigin(sender, "a".repeat(64))
    private val intent =
        CollaborationMessageIntent(
            chatId(7),
            owner,
            conversation,
            Instant.fromEpochMilliseconds(10),
            "Original text",
        )
    private val staged =
        CollaborationMessageRecord(
            intent,
            2,
            CollaborationMessageState.Staged(device, "transaction", origin),
        )
    private val original =
        CollaborationHistoryMessage(
            sender,
            intent.id,
            "\$original",
            11,
            origin.contentHash,
            intent.text,
            listOf("\$original", "\$retry"),
            false,
        )
    private val native =
        CollaborationTimelineItem(
            "native",
            "\$original",
            "transaction",
            intent.id,
            sender,
            11,
            true,
            CollaborationMessageKind.TEXT,
            intent.text,
            false,
            CollaborationMessageDelivery.SENT,
        )

    private fun rows(
        history: List<CollaborationHistoryMessage> = emptyList(),
        pending: List<CollaborationMessageRecord> = emptyList(),
        timeline: List<CollaborationTimelineItem> = emptyList(),
        source: CollaborationMessageDevice? = device,
        identity: CollaborationSelfIdentity? = self,
        includeUnmatched: Boolean = true,
    ) =
        assertIs<AppResult.Success<List<ChatMessageRow>>>(
                chatThreadRows(
                    owner,
                    conversation,
                    identity,
                    source,
                    history,
                    pending,
                    timeline,
                    includeUnmatched,
                )
            )
            .value

    @Test
    fun historicalWindowKeepsOriginalTextUntilARevisionIsAdmittedAndExcludesUnrelatedNewRows() {
        val extra = staged.copy(intent = intent.copy(id = chatId(80), text = "New pending"))
        val live = native.copy(eventId = "new-event", sender = "@peer:test", body = "New incoming")
        val projected =
            rows(
                history = listOf(original),
                pending = listOf(staged, extra),
                timeline = listOf(native.copy(body = "Edited original", edited = true), live),
                includeUnmatched = false,
            )
        assertEquals(1, projected.size)
        assertEquals("Original text", projected.single().text)
        assertEquals("outgoing:${intent.id}", projected.single().key)
        assertFalse(projected.single().edited)
    }

    @Test
    fun oneOutgoingRowKeepsItsKeyThroughQueueNativeConfirmationHistoryAndRetirement() {
        val queued = staged.copy(state = CollaborationMessageState.Queued)
        val before = rows(pending = listOf(queued)).single()
        val live = rows(pending = listOf(staged), timeline = listOf(native)).single()
        val acquired = rows(listOf(original), listOf(staged), listOf(native)).single()
        val retired = rows(listOf(original), timeline = listOf(native)).single()
        assertEquals(before.key, live.key)
        assertEquals(before.key, acquired.key)
        assertEquals(before.key, retired.key)
        assertEquals(CollaborationMessageDelivery.PENDING, before.delivery)
        assertEquals(CollaborationMessageDelivery.SENT, live.delivery)
        assertEquals(intent.text, retired.text)
        assertEquals(ChatMessageOwnership.SELF, retired.ownership)
        assertIs<CollaborationMessageState.Staged>(staged.state)
    }

    @Test
    fun aPeerCopyingLogicalIdAndTextCannotHidePendingIntent() {
        val peer = original.copy(sender = "@peer:example.test")
        val result = rows(listOf(peer), listOf(staged))
        assertEquals(2, result.size)
        assertEquals(1, result.count { it.ownership == ChatMessageOwnership.COLLEAGUE })
        assertEquals(1, result.count { it.localId == intent.id })
    }

    @Test
    fun mismatchedCompleteHashOrOriginalTextCannotMergeEvenForTheSameSender() {
        for (changed in
            listOf(
                original.copy(contentHash = "b".repeat(64)),
                original.copy(text = "Altered text"),
            )) {
            val result = rows(listOf(changed), listOf(staged))
            assertEquals(2, result.size)
            assertEquals(2, result.map { it.key }.distinct().size)
        }
    }

    @Test
    fun aConfirmedRedactionNeverRestoresThePendingPlaintext() {
        val deleted = original.copy(text = null, redacted = true)
        val result = rows(listOf(deleted), listOf(staged), listOf(native)).single()
        assertEquals(CollaborationMessageKind.REDACTED, result.kind)
        assertNull(result.text)
        assertEquals("outgoing:${intent.id}", result.key)
    }

    @Test
    fun oldBootstrapDeviceRoomOrFamilyCannotReuseTheSameNativeTransaction() {
        for (source in
            listOf(
                device.copy(bootstrapOperationId = chatId(8)),
                device.copy(deviceId = chatId(8)),
                device.copy(nativeSessionId = chatId(8)),
                device.copy(roomId = "!other:example.test"),
            )) {
            val result = rows(pending = listOf(staged), timeline = listOf(native), source = source)
            assertEquals(2, result.size)
            assertEquals(
                CollaborationMessageDelivery.PENDING,
                result.single { it.localId != null }.delivery,
            )
        }
    }

    @Test
    fun nativeMappingRequiresSenderOwnFlagLogicalIdAndTransaction() {
        for (item in
            listOf(
                native.copy(sender = "@peer:example.test"),
                native.copy(own = false),
                native.copy(logicalId = chatId(9)),
                native.copy(transactionId = "another"),
            )) {
            val result = rows(pending = listOf(staged), timeline = listOf(item))
            assertEquals(2, result.size)
            assertEquals(
                CollaborationMessageDelivery.PENDING,
                result.single { it.localId != null }.delivery,
            )
        }
    }

    @Test
    fun remoteEchoWithOriginalTransactionBecomesOneCheckedBubbleBeforeHistoryArrives() {
        val result = rows(pending = listOf(staged), timeline = listOf(native)).single()
        assertEquals("outgoing:${intent.id}", result.key)
        assertEquals(CollaborationMessageDelivery.SENT, result.delivery)
        assertEquals(intent.text, result.text)
        assertEquals(
            result.key,
            rows(listOf(original), listOf(staged), listOf(native)).single().key,
        )
    }

    @Test
    fun exactDurableSentEventMatchesARemoteEchoWithoutUnsignedTransaction() {
        val sent =
            staged.copy(
                state = CollaborationMessageState.Sent(device, "transaction", "\$original", origin)
            )
        val remote = native.copy(transactionId = null)
        val result = rows(pending = listOf(sent), timeline = listOf(remote)).single()
        assertEquals("outgoing:${intent.id}", result.key)
        assertEquals(CollaborationMessageDelivery.SENT, result.delivery)
        for (other in
            listOf(
                remote.copy(eventId = "\$another"),
                remote.copy(sender = "@other:example.test"),
                remote.copy(own = false),
                remote.copy(logicalId = chatId(9)),
                remote.copy(transactionId = "conflicting"),
            )) assertEquals(2, rows(pending = listOf(sent), timeline = listOf(other)).size)
        assertEquals(
            2,
            rows(
                    pending = listOf(sent),
                    timeline = listOf(remote),
                    source = device.copy(deviceId = chatId(8)),
                )
                .size,
        )
    }

    @Test
    fun remoteEchoWithoutOriginalTransactionOrReceiptDoesNotAcknowledgeByTextAlone() {
        val result =
            rows(pending = listOf(staged), timeline = listOf(native.copy(transactionId = null)))
        assertEquals(2, result.size)
        assertEquals(
            CollaborationMessageDelivery.PENDING,
            result.single { it.localId != null }.delivery,
        )
    }

    @Test
    fun unownedLocalEchoesAndMissingOriginalEvidenceDoNotInventAnotherSend() {
        assertTrue(rows(timeline = listOf(native.copy(eventId = null))).isEmpty())
        val legacy = staged.copy(state = CollaborationMessageState.Staged(device, "transaction"))
        assertEquals(2, rows(listOf(original), listOf(legacy)).size)
        assertEquals(
            1,
            rows(pending = listOf(legacy), timeline = listOf(native.copy(eventId = null))).size,
        )
    }

    @Test
    fun canonicalAliasesSuppressDuplicateProviderEventsAndVerifiedEditsOnlyAffectDisplay() {
        val edit = native.copy(eventId = "\$retry", body = "Edited text", edited = true)
        val stored =
            original.copy(
                latestEdit =
                    CollaborationMessageRevision(
                        CollaborationMessageEdit(
                            "\$revision",
                            sender,
                            "\$retry",
                            20,
                            "Edited text",
                            "b".repeat(64),
                            originalLogicalId = intent.id,
                        ),
                        12,
                    )
            )
        val result = rows(listOf(stored), timeline = listOf(edit)).single()
        assertEquals("Edited text", result.text)
        assertTrue(result.edited)
        assertEquals("Original text", original.text)
        assertEquals(result, rows(listOf(stored)).single())
        val withPending = rows(listOf(stored), listOf(staged), listOf(edit)).single()
        assertEquals("outgoing:" + intent.id, withPending.key)
        assertEquals(result.text, withPending.text)
        assertEquals(original.timestamp, withPending.timestamp)
        val spoof = edit.copy(sender = "@other:example.test")
        assertEquals(
            "Original text",
            rows(listOf(original), timeline = listOf(spoof)).single().text,
        )
        assertTrue(rows(timeline = listOf(edit)).isEmpty())
    }

    @Test
    fun unknownLocalIdentityDoesNotGuessOwnershipFromAnUntrustedLogicalId() {
        val result = rows(listOf(original), identity = null).single()
        assertEquals(ChatMessageOwnership.UNKNOWN, result.ownership)
        assertEquals(
            ChatMessageOwnership.SELF,
            rows(pending = listOf(staged), identity = null).single().ownership,
        )
    }

    @Test
    fun foreignIntentMissingTimelineSourceAndOversizedInputsFailBeforeRendering() {
        for (record in
            listOf(
                staged.copy(intent = intent.copy(owner = owner.copy(accountId = chatId(8)))),
                staged.copy(intent = intent.copy(conversationId = chatId(8))),
            )) {
            assertIs<AppResult.Failed>(
                chatThreadRows(
                    owner,
                    conversation,
                    self,
                    device,
                    emptyList(),
                    listOf(record),
                    emptyList(),
                )
            )
        }
        assertIs<AppResult.Failed>(
            chatThreadRows(
                owner,
                conversation,
                self,
                null,
                emptyList(),
                emptyList(),
                listOf(native),
            )
        )
        assertIs<AppResult.Failed>(
            chatThreadRows(
                owner,
                conversation,
                self,
                device,
                List(501) { original },
                emptyList(),
                emptyList(),
            )
        )
        assertIs<AppResult.Failed>(
            chatThreadRows(
                owner,
                conversation,
                self,
                device,
                emptyList(),
                listOf(staged, staged),
                emptyList(),
            )
        )
    }
}
