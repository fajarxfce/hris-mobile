package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*

internal fun notificationReplyCycleResult(
    parent: CollaborationNativeParent,
    expected: CollaborationNotificationReplyRecoveryCursor?,
    after: Long,
    cycle: CollaborationNotificationReplyRecoveryCycle,
    result: AppResult<CollaborationNotificationReplyRecoveryCursor?>,
): AppResult<CollaborationNotificationReplyRecoverySnapshot> {
    if (result is AppResult.Failed) return result
    val value =
        (result as AppResult.Success).value
            ?: return notificationReplyCycleFailure(FailureKind.Conflict)
    if (
        !validCollaborationNotificationReplyRecoveryCursor(value) ||
            value.generation == expected?.generation ||
            value.afterSequence != after ||
            value.cycle != cycle
    )
        return notificationReplyCycleFailure(FailureKind.Conflict)
    return AppResult.Success(CollaborationNotificationReplyRecoverySnapshot(parent, value))
}

internal fun notificationReplyCycleFailure(kind: FailureKind) =
    collaborationSetupFailure("client.collaboration_notification_reply_cycle_unavailable", kind)
