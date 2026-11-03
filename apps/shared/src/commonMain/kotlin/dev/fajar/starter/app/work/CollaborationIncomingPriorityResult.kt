package dev.fajar.starter.app.work

import dev.fajar.starter.app.notifications.CollaborationNotificationCandidate
import dev.fajar.starter.sync.domain.SyncResult

/** Opaque retained IDs after finite acquisition has released its runtime; not display authority. */
data class CollaborationIncomingPriorityResult(
    val result: SyncResult,
    val candidate: CollaborationNotificationCandidate?,
)
