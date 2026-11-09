package dev.fajar.starter.collaboration.domain.entities

/**
 * One raw inventory page is claimed durably before SDK work. Null conversation is an empty page.
 */
data class CollaborationSpaceRecoveryClaim(val conversationId: String?, val nextAfterId: String?)

data class CollaborationSpaceRecoverySweep(
    val wakeGeneration: String,
    val boundary: CollaborationSpaceRoomBindingBoundary? = null,
    val afterId: String? = null,
    val pending: CollaborationSpaceRecoveryClaim? = null,
    val reachedEnd: Boolean = false,
    val retryRequired: Boolean = false,
    val continuationRequired: Boolean = false,
    val hasNewerBindings: Boolean = false,
) {
    val cursor: CollaborationSpaceRoomBindingCursor?
        get() = afterId?.let { CollaborationSpaceRoomBindingCursor(requireNotNull(boundary), it) }
}

/** Local facts only; neither the native family nor this cursor grants delivery authority. */
data class CollaborationSpaceRecoveryProgress(
    val generation: String,
    val sweep: CollaborationSpaceRecoverySweep?,
)

data class CollaborationSpaceRecoverySnapshot(
    val owner: CollaborationSpaceOwner,
    val progress: CollaborationSpaceRecoveryProgress?,
)

sealed interface CollaborationSpaceRecoveryChange {
    data class Begin(val wake: CollaborationSpaceRecoveryWake) : CollaborationSpaceRecoveryChange

    /** Supply a page from the bounded inventory with limit=1, using the retained cursor. */
    data class Attempt(val page: CollaborationSpaceRoomBindingPage) :
        CollaborationSpaceRecoveryChange

    data class Complete(val retryRequired: Boolean, val continuationRequired: Boolean = false) :
        CollaborationSpaceRecoveryChange

    data object Finish : CollaborationSpaceRecoveryChange
}

data class CollaborationSpaceRecoveryUpdate(
    val snapshot: CollaborationSpaceRecoverySnapshot,
    /** Only a durably finished clean sweep can discharge this exact wake generation. */
    val completedWake: CollaborationSpaceRecoveryWake?,
)
