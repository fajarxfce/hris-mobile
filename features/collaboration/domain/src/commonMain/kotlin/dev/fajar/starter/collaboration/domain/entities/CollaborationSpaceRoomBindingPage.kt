package dev.fajar.starter.collaboration.domain.entities

/** Stable local snapshot coordinates. They confer no current content or SDK authority. */
data class CollaborationSpaceRoomBindingBoundary(
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val generation: String,
    val revision: Long,
)

/** Raw index continuation, independent of login ID and conversation ordering. */
data class CollaborationSpaceRoomBindingCursor(
    val boundary: CollaborationSpaceRoomBindingBoundary,
    val afterId: String,
)

data class CollaborationSpaceRoomBindingPage(
    val boundary: CollaborationSpaceRoomBindingBoundary?,
    val bindings: List<CollaborationSpaceRoomBinding>,
    val next: CollaborationSpaceRoomBindingCursor?,
    val hasNewerBindings: Boolean,
)
