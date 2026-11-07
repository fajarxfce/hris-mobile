package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

class CollaborationInboxPolicyTest {
    private val access = CollaborationLocalAccess("session", "audience", "account", "company")
    private val device = CollaborationMessageDevice("native", "device", "bootstrap", "!room:test")
    private val history =
        CollaborationHistoryPage(
            listOf(
                CollaborationHistoryMessage(
                    "@peer:test",
                    null,
                    "\$old",
                    1,
                    "a".repeat(64),
                    "Old",
                    listOf("\$old"),
                    false,
                )
            ),
            null,
            roomId = device.roomId,
        )

    private fun record(
        id: Int,
        state: CollaborationMessageState = CollaborationMessageState.Queued,
    ) =
        CollaborationMessageRecord(
            CollaborationMessageIntent(
                "id-$id",
                access.messageOwner(),
                "conversation",
                Instant.fromEpochMilliseconds(id.toLong()),
                "Text $id",
            ),
            0,
            state,
        )

    private fun preview(
        commands: List<CollaborationMessageRecord>,
        page: CollaborationHistoryPage = history,
        draft: CollaborationDraft? = null,
    ) =
        assertIs<AppResult.Success<CollaborationInboxPreview>>(
                collaborationInboxPreview("conversation", page, draft, commands)
            )
            .value

    @Test
    fun latestOriginalSendWinsBeforeDuringAndAfterAcknowledgement() {
        val first = record(2)
        val latest = record(3)
        assertEquals(latest, preview(listOf(latest, first)).outgoing)
        val firstSent = first.copy(state = CollaborationMessageState.Sent(device, "txn-2", "\$two"))
        assertEquals(latest, preview(listOf(firstSent, latest)).outgoing)
        val latestSent =
            latest.copy(state = CollaborationMessageState.Sent(device, "txn-3", "\$three"))
        assertEquals(latestSent, preview(listOf(first, latestSent)).outgoing)
        val accepted =
            history.messages
                .single()
                .copy(
                    primaryEventId = "\$three",
                    eventIds = listOf("\$three"),
                    text = latest.intent.text,
                )
        assertNull(
            preview(listOf(first, latestSent), history.copy(messages = listOf(accepted))).outgoing
        )
    }

    @Test
    fun pendingIsVisibleEvenWhenLocalClockIsBehindServerAndNoHistoryExists() {
        val pending = record(2)
        assertEquals(
            pending,
            preview(
                    listOf(pending),
                    history.copy(messages = listOf(history.messages.single().copy(timestamp = 99))),
                )
                .outgoing,
        )
        assertEquals(
            pending,
            preview(listOf(pending), CollaborationHistoryPage(emptyList(), null)).outgoing,
        )
    }

    @Test
    fun cancelledAndOtherConversationCommandsCannotReplaceLatestPreview() {
        val pending = record(2)
        val foreignRoom =
            record(4).let { it.copy(intent = it.intent.copy(conversationId = "another")) }
        assertEquals(
            pending,
            preview(listOf(record(3, CollaborationMessageState.Cancelled), pending, foreignRoom))
                .outgoing,
        )
    }

    @Test
    fun editingAnEarlierMessageDoesNotAdvertiseItAsANewSend() {
        val latest = record(2)
        val edit =
            record(3).let {
                it.copy(
                    intent =
                        it.intent.copy(
                            editTarget =
                                CollaborationEditTarget(
                                    device.roomId,
                                    "\$old",
                                    null,
                                    "@self:test",
                                    "a".repeat(64),
                                    0,
                                    0,
                                )
                        )
                )
            }
        assertEquals(latest, preview(listOf(latest, edit)).outgoing)
    }

    @Test
    fun matchedRedactionOrEditWinsOverOriginalOutgoingTextWithoutInventingAnAck() {
        val pending =
            record(
                2,
                CollaborationMessageState.Staged(
                    device,
                    "txn",
                    CollaborationMessageOrigin("@self:test", "a".repeat(64)),
                ),
            )
        val message =
            history.messages
                .single()
                .copy(
                    sender = "@self:test",
                    logicalId = pending.intent.id,
                    text = null,
                    redacted = true,
                )
        assertNull(preview(listOf(pending), history.copy(messages = listOf(message))).outgoing)
        assertEquals(
            pending,
            preview(
                    listOf(pending),
                    history.copy(messages = listOf(message.copy(sender = "@other:test"))),
                )
                .outgoing,
        )
        assertEquals(
            pending,
            preview(
                    listOf(pending),
                    history.copy(messages = listOf(message.copy(contentHash = "b".repeat(64)))),
                )
                .outgoing,
        )
    }

    @Test
    fun newDraftOverridesPendingButExactFrozenSubmissionIsNotCalledDraftAgain() {
        val pending = record(2)
        val draft =
            CollaborationDraft(
                "conversation",
                "revision",
                "Next thought",
                Instant.fromEpochMilliseconds(3),
            )
        assertEquals(draft, preview(listOf(pending), draft = draft).draft)
        val frozen = draft.copy(text = pending.intent.text, submissionId = pending.intent.id)
        assertNull(preview(listOf(pending), draft = frozen).draft)
        assertEquals(frozen, preview(emptyList(), draft = frozen).draft)
        assertEquals(
            frozen.copy(text = "Different"),
            preview(listOf(pending), draft = frozen.copy(text = "Different")).draft,
        )
    }

    @Test
    fun oldSentCommandCannotDisplaceMoreRecentHistory() {
        val sent = record(2, CollaborationMessageState.Sent(device, "txn", "\$two"))
        assertNull(
            preview(
                    listOf(sent),
                    history.copy(messages = listOf(history.messages.single().copy(timestamp = 3))),
                )
                .outgoing
        )
    }

    @Test
    fun commandSnapshotDetachesMutableInventoryAndStagingKeysBeforeSuspension() {
        val keys = mutableSetOf("original")
        val commands = mutableListOf(record(2, CollaborationMessageState.Staging(device, keys)))
        val result =
            assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                    collaborationInboxCommands(access, commands)
                )
                .value
        commands.clear()
        keys.clear()
        assertEquals(1, result.size)
        assertEquals(
            setOf("original"),
            assertIs<CollaborationMessageState.Staging>(result.single().state).before,
        )
    }
}
