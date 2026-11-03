package dev.fajar.starter.app.notifications

/**
 * Public localized copy sampled once per publication/reconciliation, without restarting its owner.
 */
fun interface CollaborationNotificationCopySource {
    fun current(): CollaborationNotificationCopy
}
