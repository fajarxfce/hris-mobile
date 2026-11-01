package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

class ChatThreadProjectionTest {
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
    ) =
        assertIs<AppResult.Success<List<ChatMessageRow>>>(
                chatThreadRows(owner, conversation, identity, source, history, pending, timeline)
            )
            .value

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
        val result = rows(listOf(original), timeline = listOf(edit)).single()
        assertEquals("Edited text", result.text)
        assertTrue(result.edited)
        assertEquals("Original text", original.text)
        val spoof = edit.copy(sender = "@other:example.test")
        assertEquals(
            "Original text",
            rows(listOf(original), timeline = listOf(spoof)).single().text,
        )
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
