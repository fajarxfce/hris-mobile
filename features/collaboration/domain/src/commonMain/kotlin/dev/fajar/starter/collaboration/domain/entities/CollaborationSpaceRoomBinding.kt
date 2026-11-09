package dev.fajar.starter.collaboration.domain.entities

/** Immutable owned metadata. Retention grants no SDK, content or message-delivery authority. */
data class CollaborationSpaceRoomBinding(
    val conversation: CollaborationSpaceConversationMetadata,
    val room: CollaborationSpacePublishedRoom,
    val original: CollaborationSpaceOriginalRoomMetadata? = null,
) {
    override fun toString() = "CollaborationSpaceRoomBinding([metadata])"
}

/** Complete original facts acquired by exact company/conversation coordinates. */
data class CollaborationSpaceOriginalRoomMetadata(
    val conversation: CollaborationOriginalConversation,
    val room: CollaborationOriginalRoom,
) {
    override fun toString() = "CollaborationSpaceOriginalRoomMetadata([metadata])"
}
