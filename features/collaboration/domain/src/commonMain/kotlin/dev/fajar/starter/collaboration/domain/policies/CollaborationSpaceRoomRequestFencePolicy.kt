package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceRoomRequestFence(
    conversation: CollaborationSpaceConversation,
    evidence: CollaborationSpaceRoomRequestFence,
): Boolean =
    validCollaborationSpaceRoomRequestIntent(conversation, evidence.original) &&
        validCollaborationSpaceRoomRequestFenceEvidence(evidence.original, evidence) &&
        evidence.createdAt >= conversation.createdAt

/**
 * Durable evidence matches the immutable original; current time and native authority are separate.
 */
fun validCollaborationSpaceRoomRequestFenceEvidence(
    original: CollaborationSpaceRoomRequestIntent,
    evidence: CollaborationSpaceRoomRequestFence,
): Boolean =
    evidence.original == original &&
        validCollaborationSpaceConversationId(evidence.id) &&
        evidence.createdAt.epochSeconds in -62_135_596_800L..253_402_300_799L &&
        evidence.createdAt.nanosecondsOfSecond % 1000 == 0
