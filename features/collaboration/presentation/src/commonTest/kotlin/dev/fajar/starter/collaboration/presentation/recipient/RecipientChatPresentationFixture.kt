package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceRecipientMessageIntent
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatRowsFixture
import dev.fajar.starter.common.result.AppResult
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

internal class RecipientChatPresentationFixture {
    val rows = SpaceChatRowsFixture()
    val access = rows.access
    val conversation = rows.base.metadata
    val peer =
        listOf(conversation.firstAccountId, conversation.secondAccountId).single {
            it != access.accountId
        }
    val first = original(1)
    val pending = RecipientComposerPendingSubmission(first)

    fun original(index: Int) =
        CollaborationSpaceRecipientSubmission(
            CollaborationSpaceRecipientDraft(
                peer,
                chatId(900 + index),
                "Recipient $index",
                rows.base.base.now + index.milliseconds,
            ),
            chatId(2000 + index),
            rows.base.base.now + index.milliseconds,
        )

    fun record(original: CollaborationSpaceRecipientSubmission = first) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceRecipientMessageIntent(access, original, conversation.id),
            0,
            CollaborationMessageState.Queued,
        )

    fun sent(original: CollaborationSpaceRecipientSubmission = first) =
        rows.sent().copy(intent = record(original).intent)

    fun read(
        previous: RecipientChatPresentation = RecipientChatPresentation(),
        source: List<CollaborationSpaceRecipientSubmission> = emptyList(),
        provisional: List<RecipientComposerPendingSubmission> = emptyList(),
        progress: List<CollaborationSpaceRecipientMessageProgress> = emptyList(),
        thread: CollaborationSpaceThreadSnapshot? = null,
        conversation: CollaborationSpaceConversationMetadata? = this.conversation,
    ) =
        projectRecipientChatPresentation(
            access,
            peer,
            conversation,
            previous,
            source,
            provisional,
            progress,
            thread,
        )

    fun success(result: AppResult<RecipientChatPresentation>) =
        assertIs<AppResult.Success<RecipientChatPresentation>>(result).value
}
