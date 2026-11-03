package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationInputRepository
import dev.fajar.starter.common.result.*

/** At most four metadata pages; no private input is decrypted or returned by this discovery. */
internal suspend fun readNotificationInputReferences(
    inputs: CollaborationNotificationInputRepository,
    guard: suspend () -> AppResult<Unit>,
): AppResult<List<CollaborationNotificationInputReference>> {
    val references = mutableListOf<CollaborationNotificationInputReference>()
    while (references.size < COLLABORATION_NOTIFICATION_INPUT_CAPACITY) {
        val limit = minOf(20, COLLABORATION_NOTIFICATION_INPUT_CAPACITY - references.size)
        val after = references.lastOrNull()?.id
        val result = inputs.page(after, limit)
        guard().let { if (it is AppResult.Failed) return it }
        if (result is AppResult.Failed) return result
        val page = (result as AppResult.Success).value
        if (
            page.size > limit ||
                page.any {
                    !validCollaborationNotificationInputReference(it) ||
                        after != null && it.id <= after
                }
        )
            return notificationInputRecoveryFailure(FailureKind.Conflict)
        val ids = page.map { it.id }
        if (ids != ids.distinct().sorted())
            return notificationInputRecoveryFailure(FailureKind.Conflict)
        references += page
        if (page.size < limit) break
    }
    return AppResult.Success(references.toList())
}

internal fun notificationInputRecoveryResult(
    parent: CollaborationNativeParent,
    expected: CollaborationNotificationInputRecoveryCursor?,
    state: CollaborationNotificationInputRecoveryState,
    result: AppResult<CollaborationNotificationInputRecoveryCursor?>,
): AppResult<CollaborationNotificationInputRecoverySnapshot> {
    if (result is AppResult.Failed) return result
    val value =
        (result as AppResult.Success).value
            ?: return notificationInputRecoveryFailure(FailureKind.Conflict)
    if (
        !validCollaborationNotificationInputRecoveryCursor(value) ||
            value.generation == expected?.generation ||
            value.state != state
    )
        return notificationInputRecoveryFailure(FailureKind.Conflict)
    return AppResult.Success(CollaborationNotificationInputRecoverySnapshot(parent, value))
}

internal fun notificationInputRecoveryFailure(kind: FailureKind) =
    collaborationSetupFailure("client.collaboration_notification_input_recovery_unavailable", kind)
