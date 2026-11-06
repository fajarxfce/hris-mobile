package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlin.time.Instant

class ChatEditProjectionTest {
    private val sender = "@self:example.test"
    private val owner =
        CollaborationMessageOwner("https://hris.example.test/", chatId(1), chatId(2))
    private val conversation = chatId(3)
    private val device =
        CollaborationMessageDevice(chatId(4), chatId(5), chatId(6), "!room:example.test")
    private val original =
        CollaborationHistoryMessage(
            sender,
            chatId(7),
            "\$original",
            1000,
            "a".repeat(64),
            "Original",
            listOf("\$original"),
            false,
            firstAcceptedAt = 1000,
        )
    private val target =
        CollaborationEditTarget(
            device.roomId,
            original.primaryEventId,
            original.logicalId,
            sender,
            original.contentHash,
            original.timestamp,
            1000,
        )
    private val intent =
        CollaborationMessageIntent(
            chatId(8),
            owner,
            conversation,
            Instant.fromEpochMilliseconds(2000),
            "Updated",
            editTarget = target,
        )
    private val record = CollaborationMessageRecord(intent, 0, CollaborationMessageState.Queued)
    private val origin = CollaborationMessageOrigin(sender, "b".repeat(64))

    @Test
    fun anOfflineEditChangesOneOriginalWithoutMovingItsKeyTimeOrReadIdentity() {
        val before = rows(emptyList(), emptyList()).single()
        val after = rows(listOf(record), listOf(outcome(record))).single()
        assertEquals("Updated", after.text)
        assertEquals(before.key, after.key)
        assertEquals(before.timestamp, after.timestamp)
        assertEquals(before.reference, after.reference)
        assertEquals(before.eventId, after.eventId)
        assertEquals(CollaborationMessageDelivery.SENT, after.delivery)
        assertEquals(CollaborationMessageDelivery.PENDING, after.editDelivery)
        assertTrue(after.edited)
        assertFalse(after.canCancel)
    }

    @Test
    fun restoredStagedEditDoesNotNeedATimelineAndNeverCreatesAnUnmatchedBubble() {
        val staged =
            record.copy(
                revision = 2,
                state = CollaborationMessageState.Staged(device, "tx-edit", origin),
            )
        assertEquals(
            "Updated",
            rows(listOf(staged), listOf(outcome(staged)), source = null).single().text,
        )
        assertTrue(rows(listOf(staged), listOf(outcome(staged)), history = emptyList()).isEmpty())
        assertEquals(
            "Updated",
            rows(listOf(staged), listOf(outcome(staged)), unmatched = false).single().text,
        )
    }

    @Test
    fun exactNativeEditEchoSuppliesFailureWithoutShowingTheFallbackAsAnotherMessage() {
        val staged =
            record.copy(
                revision = 2,
                state = CollaborationMessageState.Staged(device, "tx-edit", origin),
            )
        val echo =
            CollaborationTimelineItem(
                "sdk-edit",
                "\$edit",
                "tx-edit",
                null,
                sender,
                2000,
                true,
                CollaborationMessageKind.TEXT,
                "* Updated",
                false,
                CollaborationMessageDelivery.FAILED,
            )
        val row = rows(listOf(staged), listOf(outcome(staged)), timeline = listOf(echo)).single()
        assertEquals("Updated", row.text)
        assertEquals(CollaborationMessageDelivery.FAILED, row.editDelivery)
        assertEquals("\$original", row.reference?.eventId)
        assertEquals(CollaborationMessageDelivery.SENT, row.delivery)
    }

    @Test
    fun observedRejectedChangeRestoresAcceptedTextAndRetainsItsFailureAcrossProjection() {
        val rejected = outcome(record, CollaborationEditOutcomeKind.NOT_APPLIED)
        val row = rows(listOf(record), listOf(rejected)).single()
        assertEquals("Original", row.text)
        assertNull(row.editDelivery)
        assertEquals(listOf(record.intent.id), row.failedEditIds)
        assertEquals("Updated", row.failedEditText)
        assertEquals(row, rows(listOf(record), listOf(rejected)).single())
    }

    @Test
    fun newerAcceptedChangeDoesNotHideAnOlderUnacknowledgedFailure() {
        val newer =
            record.copy(
                intent =
                    intent.copy(
                        id = chatId(9),
                        text = "Newer",
                        createdAt = Instant.fromEpochMilliseconds(3000),
                    )
            )
        val edited =
            original.copy(
                latestEdit =
                    CollaborationMessageRevision(
                        CollaborationMessageEdit(
                            "\$newer",
                            sender,
                            original.primaryEventId,
                            3000,
                            "Newer",
                            "c".repeat(64),
                            newer.intent.id,
                            original.logicalId,
                        ),
                        1000,
                    )
            )
        val row =
            rows(
                    listOf(record, newer),
                    listOf(
                        outcome(record, CollaborationEditOutcomeKind.NOT_APPLIED),
                        outcome(newer, CollaborationEditOutcomeKind.OBSERVED),
                    ),
                    history = listOf(edited),
                )
                .single()
        assertEquals("Newer", row.text)
        assertEquals(listOf(record.intent.id), row.failedEditIds)
        assertNull(row.editDelivery)
        val acknowledged =
            rows(
                    listOf(newer),
                    listOf(outcome(newer, CollaborationEditOutcomeKind.OBSERVED)),
                    history = listOf(edited),
                )
                .single()
        assertEquals(row.text, acknowledged.text)
        assertTrue(acknowledged.failedEditIds.isEmpty())
        assertNull(acknowledged.failedEditText)
        assertEquals(row.key, acknowledged.key)
    }

    @Test
    fun unappliedTextIsChronologicalAndExcludesPendingObservedAndOtherRoomEdits() {
        fun change(id: Int, text: String, time: Long) =
            record.copy(
                intent =
                    intent.copy(
                        id = chatId(id),
                        text = text,
                        createdAt = Instant.fromEpochMilliseconds(time),
                    )
            )
        val earlier = change(10, "First attempt", 1100)
        val later = change(11, "Second attempt", 1200)
        val pending = change(12, "Pending attempt", 1300)
        val observed = change(13, "Accepted attempt", 1400)
        val foreign =
            change(14, "Other room", 1500).let {
                it.copy(
                    intent =
                        it.intent.copy(editTarget = target.copy(roomId = "!other:example.test"))
                )
            }
        val commands = listOf(later, pending, foreign, earlier, observed)
        val states =
            commands.map {
                outcome(
                    it,
                    when (it) {
                        pending -> CollaborationEditOutcomeKind.AWAITING_PROVIDER
                        observed -> CollaborationEditOutcomeKind.OBSERVED
                        else -> CollaborationEditOutcomeKind.NOT_APPLIED
                    },
                )
            }
        val result = rows(commands, states, source = null).single()
        assertEquals("First attempt\n\nSecond attempt", result.failedEditText)
        assertEquals(listOf(earlier.intent.id, later.intent.id).sorted(), result.failedEditIds)
        assertEquals("Original", result.text)
        assertNull(rows(commands, states, roomId = null).single().failedEditText)
        assertNull(rows(commands, states, self = null).single().failedEditText)
        assertNull(
            rows(commands, states, history = listOf(original.copy(text = null, redacted = true)))
                .single()
                .failedEditText
        )
    }

    @Test
    fun newerObservedOrArchivedLocalEditCannotBeOverwrittenByAnOlderUnresolvedEdit() {
        val newer =
            record.copy(
                intent =
                    intent.copy(id = chatId(9), createdAt = Instant.fromEpochMilliseconds(3000))
            )
        assertEquals(
            "Original",
            rows(
                    listOf(record, newer),
                    listOf(outcome(record), outcome(newer, CollaborationEditOutcomeKind.OBSERVED)),
                )
                .single()
                .text,
        )
        assertEquals(
            "Original",
            rows(listOf(record), listOf(outcome(record, CollaborationEditOutcomeKind.SUPERSEDED)))
                .single()
                .text,
        )
    }

    @Test
    fun unconfirmedOwnershipRedactionAndUnknownOutcomesCannotExposeAnOptimisticEdit() {
        assertEquals("Original", rows(listOf(record), emptyList()).single().text)
        assertEquals(
            "Original",
            rows(listOf(record), listOf(outcome(record)), roomId = "!different:example.test")
                .single()
                .text,
        )
        assertEquals(
            "Original",
            rows(listOf(record), listOf(outcome(record)), roomId = null).single().text,
        )
        assertEquals(
            "Original",
            rows(listOf(record), listOf(outcome(record)), self = null).single().text,
        )
        val redacted =
            rows(
                    listOf(record),
                    listOf(outcome(record)),
                    history = listOf(original.copy(text = null, redacted = true)),
                )
                .single()
        assertNull(redacted.text)
        assertNull(redacted.editDelivery)
        assertTrue(redacted.failedEditIds.isEmpty())
        assertIs<AppResult.Failed>(
            chatThreadRows(
                owner,
                conversation,
                CollaborationSelfIdentity(sender),
                device,
                listOf(original),
                listOf(record),
                emptyList(),
                editOutcomes =
                    listOf(
                        CollaborationEditOutcome(chatId(99), CollaborationEditOutcomeKind.OBSERVED)
                    ),
            )
        )
    }

    private fun outcome(
        record: CollaborationMessageRecord,
        kind: CollaborationEditOutcomeKind = CollaborationEditOutcomeKind.AWAITING_PROVIDER,
    ) = CollaborationEditOutcome(record.intent.id, kind)

    private fun rows(
        pending: List<CollaborationMessageRecord>,
        outcomes: List<CollaborationEditOutcome>,
        history: List<CollaborationHistoryMessage> = listOf(original),
        timeline: List<CollaborationTimelineItem> = emptyList(),
        source: CollaborationMessageDevice? = device,
        self: CollaborationSelfIdentity? = CollaborationSelfIdentity(sender),
        unmatched: Boolean = true,
        roomId: String? = device.roomId,
    ) =
        assertIs<AppResult.Success<List<ChatMessageRow>>>(
                chatThreadRows(
                    owner,
                    conversation,
                    self,
                    source,
                    history,
                    pending,
                    timeline,
                    includeUnmatched = unmatched,
                    editOutcomes = outcomes,
                    historyRoomId = roomId,
                )
            )
            .value
}
