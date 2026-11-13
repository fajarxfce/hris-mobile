package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

internal class SpaceChatRowsFixture {
    val base = SpaceChatThreadFixture()
    val access = base.access
    val conversationId = base.conversationId
    val room = "!room:example.test"
    val sender = "@fictional:example.test"
    val company =
        CollaborationLocalAccess(access.sessionId, access.audience, access.accountId, chatId(890))
    val originalLocation = CollaborationSpaceHistoryLocation.Original(company, chatId(891), room)

    fun original(index: Int = 1) =
        CollaborationComposerSubmission(
            CollaborationDraft(
                conversationId,
                chatId(900 + index),
                "Original $index",
                base.base.now + index.milliseconds,
            ),
            chatId(2000 + index),
            base.base.now + index.milliseconds,
        )

    fun record(index: Int = 1) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceComposerIntent(access, original(index)),
            0,
            CollaborationMessageState.Queued,
        )

    fun sent(index: Int = 1) =
        record(index)
            .copy(
                revision = 3,
                state =
                    CollaborationMessageState.Sent(
                        CollaborationMessageDevice(chatId(991), chatId(992), chatId(993), room),
                        "transaction-$index",
                        "${'$'}event-$index",
                        CollaborationMessageOrigin(sender, "a".repeat(64)),
                    ),
            )

    fun history(record: CollaborationSpaceMessageRecord = sent()): CollaborationHistoryMessage {
        val sent = record.state as CollaborationMessageState.Sent
        val origin = requireNotNull(sent.origin)
        return CollaborationHistoryMessage(
                origin.sender,
                record.intent.id,
                sent.eventId,
                record.intent.createdAt.toEpochMilliseconds(),
                origin.contentHash,
                record.intent.text,
                listOf(sent.eventId),
                false,
            )
            .also { assertTrue(validCollaborationHistoryMessage(it)) }
    }

    fun originalRecord(record: CollaborationSpaceMessageRecord = sent()) =
        CollaborationMessageRecord(
            CollaborationMessageIntent(
                record.intent.id,
                company.messageOwner(),
                originalLocation.conversationId,
                record.intent.createdAt,
                record.intent.text,
                record.intent.replyToEventId,
                record.intent.forwarded,
            ),
            record.revision,
            record.state,
        )

    fun thread(
        history: List<CollaborationHistoryMessage> = emptyList(),
        commands: List<CollaborationSpaceMessageRecord> = emptyList(),
        originals: List<CollaborationMessageRecord> = emptyList(),
        location: CollaborationSpaceHistoryLocation =
            CollaborationSpaceHistoryLocation.Space(access, conversationId, room),
        selected: AppResult<CollaborationSpaceIdentity?> = AppResult.Success(null),
        original: AppResult<CollaborationSelfIdentity?> = AppResult.Success(null),
    ) =
        CollaborationSpaceThreadSnapshot.Bound(
            CollaborationSpaceThreadHistory(
                access,
                conversationId,
                location,
                CollaborationHistoryPage(history, null, roomId = location.roomId),
            ),
            commands,
            originals,
            base.metadata,
            CollaborationSpaceThreadParticipants(AppResult.Success(null), selected, original),
        )

    fun composer(vararg indices: Int) =
        SpaceChatComposerPresentation(
            pending = indices.map { SpaceChatPendingSubmission(original(it), false, null) }
        )

    fun rows(
        thread: CollaborationSpaceThreadSnapshot? = null,
        composer: SpaceChatComposerPresentation = SpaceChatComposerPresentation(),
        previous: List<SpaceChatMessageRow> = emptyList(),
    ) =
        assertIs<AppResult.Success<List<SpaceChatMessageRow>>>(
                spaceChatThreadRows(access, conversationId, thread, composer, previous)
            )
            .value

    fun selected(sender: String = this.sender) =
        CollaborationSpaceIdentity(
            access.spaceId,
            access.accountId,
            "example.test",
            sender,
            CollaborationSpaceIdentityOrigin.SPACE,
            null,
            1,
            base.base.now,
        )
}
