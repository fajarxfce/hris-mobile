package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent

/** Advisory process coordination, not content admission or a native execution lease. */
sealed interface CollaborationNotificationSurfaceState {
    data object Pending : CollaborationNotificationSurfaceState

    @ConsistentCopyVisibility
    data class Ready
    internal constructor(
        val parent: CollaborationNativeParent,
        val isIdle: Boolean,
        internal val epoch: Any,
        internal val revision: Long,
    ) : CollaborationNotificationSurfaceState

    data object Blocked : CollaborationNotificationSurfaceState

    /** No new turns; closeAndJoin separately waits for pending work and native cleanup. */
    data object Closed : CollaborationNotificationSurfaceState
}
