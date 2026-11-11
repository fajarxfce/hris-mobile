package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceIncomingTarget(target: CollaborationSpaceIncomingTarget): Boolean =
    when (target) {
        is CollaborationSpaceIncomingTarget.Published ->
            validCollaborationMessageId(target.conversationId)
        is CollaborationSpaceIncomingTarget.Original ->
            validCollaborationOriginalConversationPosition(target.position)
    }
