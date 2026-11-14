package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceRecipientMessageIntent
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.collaboration.presentation.thread.invalidThreadProjection
import dev.fajar.starter.common.result.AppResult

/** Adapts real recipient originals after association, without manufacturing a canonical draft. */
internal fun recipientChatOutgoingMessages(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    conversation: CollaborationSpaceConversationMetadata,
    thread: CollaborationSpaceThreadSnapshot?,
    composer: RecipientChatPresentation,
    captured: CapturedRecipientChatSources,
): AppResult<List<SpaceChatOutgoingMessage>> {
    val base =
        spaceChatOutgoingMessages(access, conversation.id, thread, SpaceChatComposerPresentation())
    if (base is AppResult.Failed) return base
    if (
        captured.commands.values.any {
            it.intent.recipientOrigin?.let { origin -> origin.peerAccountId != peerAccountId } ==
                true
        }
    )
        return invalidThreadProjection()
    val originals = composer.pending + composer.represented
    if (originals.map { it.original.id }.distinct().size != originals.size)
        return invalidThreadProjection()
    val ids = originals.map { it.original.id }.toSet()
    val rows =
        (base as AppResult.Success)
            .value
            .filterNot { (it.identity.source as? SpaceChatMessageSource.GroupCommand)?.id in ids }
            .toMutableList()
    val represented = composer.represented.map { it.original.id }.toSet()
    for (item in originals) {
        val original = item.original
        val intent = collaborationSpaceRecipientMessageIntent(access, original, conversation.id)
        val selected =
            selectSpaceComposerDisplayRecord(
                access,
                intent,
                listOfNotNull(item.record, captured.commands[original.id]),
            )
        if (selected is AppResult.Failed) return selected
        rows +=
            SpaceChatOutgoingMessage(
                SpaceChatMessageIdentity(
                    access.messageOwner(),
                    conversation.id,
                    SpaceChatMessageSource.GroupCommand(original.id),
                ),
                original.submittedAt.toEpochMilliseconds(),
                original.original.text,
                intent.replyToEventId,
                false,
                (selected as AppResult.Success).value?.state,
                failed = item.failure != null,
                displayWhenUnmatched = original.id !in represented,
            )
    }
    return AppResult.Success(rows)
}
