package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Owned original facts remain valid after the native parent changes or expires. */
fun validCollaborationSpaceRoomCommand(
    conversation: CollaborationSpaceConversation,
    record: CollaborationSpaceConversationCommandRecord,
): Boolean {
    val command = record.command as? CollaborationSpaceConversationCommand.Room ?: return false
    return validCollaborationSpaceConversationCommandRecord(
        conversation.owner.localDirectoryAccess(),
        record,
    ) &&
        validCollaborationSpaceRoomRequestIntent(conversation, command.intent) &&
        command.peerAccountId in
            listOf(conversation.firstAccountId, conversation.secondAccountId) &&
        (record.outcome !is CollaborationSpaceConversationOutcome.Fenced ||
            validCollaborationSpaceRoomRequestFence(conversation, record.outcome.evidence))
}

/** An explicit retry retains both coordinates; participant revisions are not globally ordered. */
fun matchesCollaborationSpaceRoomRetry(
    view: CollaborationSpaceRoomView,
    expected: CollaborationSpaceRoomAttempt,
): Boolean =
    validCollaborationSpaceRoomView(view) &&
        view.room == null &&
        view.provisioning?.attempt == expected &&
        view.provisioning.state in
            listOf(CollaborationProvisioningState.FAILED, CollaborationProvisioningState.CANCELLED)
