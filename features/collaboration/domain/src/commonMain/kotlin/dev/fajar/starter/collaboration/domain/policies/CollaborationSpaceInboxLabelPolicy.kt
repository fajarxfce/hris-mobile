package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationSpaceInboxLabelRequest(
    access: CollaborationSpaceLocalAccess,
    conversationIds: List<String>,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        conversationIds.size <= 50 &&
        conversationIds.all(::validCollaborationSpaceConversationId) &&
        conversationIds.distinct().size == conversationIds.size

fun collaborationSpaceInboxLabelBinding(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    result: AppResult<CollaborationSpaceConversationMetadata?>,
): CollaborationSpaceInboxLabel =
    when (result) {
        is AppResult.Failed ->
            CollaborationSpaceInboxLabel(conversationId, failure = result.failure)
        is AppResult.Success -> {
            val value = result.value
            when {
                value == null -> CollaborationSpaceInboxLabel(conversationId)
                value.id != conversationId ||
                    !validCollaborationSpaceConversationMetadata(access, value) ->
                    CollaborationSpaceInboxLabel(conversationId, failure = invalidInboxLabel())
                else ->
                    CollaborationSpaceInboxLabel(
                        conversationId,
                        if (value.firstAccountId == access.accountId) value.secondAccountId
                        else value.firstAccountId,
                    )
            }
        }
    }

/** Capture only the validated immutable name; directory company labels remain outside the row. */
fun collaborationSpaceInboxLabelContact(
    access: CollaborationSpaceLocalAccess,
    binding: CollaborationSpaceInboxLabel,
    result: AppResult<CollaborationSpaceContact?>,
): CollaborationSpaceInboxLabel =
    when (result) {
        is AppResult.Failed -> binding.copy(displayName = null, failure = result.failure)
        is AppResult.Success -> {
            val contact = result.value
            when {
                contact == null -> binding.copy(displayName = null, failure = null)
                contact.accountId != binding.peerAccountId ||
                    !validCollaborationSpaceContact(contact, access.accountId) ->
                    binding.copy(displayName = null, failure = invalidInboxLabel())
                else -> binding.copy(displayName = contact.displayName, failure = null)
            }
        }
    }

private fun invalidInboxLabel() =
    Failure(
        FailureKind.Unexpected,
        "Invalid local chat label",
        code = "client.collaboration_invalid_response",
    )
