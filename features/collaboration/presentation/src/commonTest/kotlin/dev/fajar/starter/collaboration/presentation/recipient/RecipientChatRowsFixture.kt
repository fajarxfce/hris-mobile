package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.assertIs

internal class RecipientChatRowsFixture {
    val facts = RecipientChatPresentationFixture()
    val access = facts.access
    val peer = facts.peer
    val conversation = facts.conversation

    fun presentation(
        originals: List<CollaborationSpaceRecipientSubmission> = listOf(facts.first),
        records: List<CollaborationSpaceMessageRecord> = emptyList(),
        thread: CollaborationSpaceThreadSnapshot? = null,
        previous: RecipientChatPresentation = RecipientChatPresentation(),
    ) =
        facts.success(
            facts.read(
                previous = previous,
                source = originals,
                progress =
                    originals.map { original ->
                        CollaborationSpaceRecipientMessageProgress(
                            original,
                            records.find { it.intent.id == original.id },
                        )
                    },
                thread = thread,
            )
        )

    fun read(
        composer: RecipientChatPresentation = presentation(),
        thread: CollaborationSpaceThreadSnapshot? = null,
        conversation: CollaborationSpaceConversationMetadata? = this.conversation,
        previous: List<RecipientChatMessageRow> = emptyList(),
    ) = recipientChatThreadRows(access, peer, conversation, thread, composer, previous)

    fun rows(
        composer: RecipientChatPresentation = presentation(),
        thread: CollaborationSpaceThreadSnapshot? = null,
        conversation: CollaborationSpaceConversationMetadata? = this.conversation,
        previous: List<RecipientChatMessageRow> = emptyList(),
    ) =
        assertIs<AppResult.Success<List<RecipientChatMessageRow>>>(
                read(composer, thread, conversation, previous)
            )
            .value
}
