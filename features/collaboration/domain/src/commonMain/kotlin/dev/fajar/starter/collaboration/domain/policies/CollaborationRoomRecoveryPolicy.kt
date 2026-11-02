package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationRoomRecoveryPosition(position: CollaborationRoomRecoveryPosition): Boolean =
    (position.afterConversationId == null ||
        validCollaborationMessageId(position.afterConversationId)) &&
        (position.inFlightConversationId == null ||
            position.inFlightConversationId == position.afterConversationId)

fun validCollaborationRoomRecoveryProgress(progress: CollaborationRoomRecoveryProgress): Boolean =
    validCollaborationMessageId(progress.generation) &&
        validCollaborationRoomRecoveryPosition(progress.position)

internal fun nextCollaborationRoomRecoveryPosition(
    progress: CollaborationRoomRecoveryProgress?,
    change: CollaborationRoomRecoveryChange,
): AppResult<CollaborationRoomRecoveryPosition> {
    if (progress != null && !validCollaborationRoomRecoveryProgress(progress))
        return invalidRoomProgress()
    val current = progress?.position ?: CollaborationRoomRecoveryPosition()
    return when (change) {
        is CollaborationRoomRecoveryChange.Attempt -> {
            if (
                !validCollaborationMessageId(change.conversationId) ||
                    current.afterConversationId?.let { change.conversationId <= it } == true
            )
                return invalidRoomProgress()
            AppResult.Success(
                current.copy(
                    afterConversationId = change.conversationId,
                    inFlightConversationId = change.conversationId,
                    retryRequired = current.retryRequired || current.inFlightConversationId != null,
                )
            )
        }
        is CollaborationRoomRecoveryChange.Complete -> {
            if (
                !validCollaborationMessageId(change.conversationId) ||
                    current.inFlightConversationId != change.conversationId
            )
                return invalidRoomProgress()
            AppResult.Success(
                current.copy(
                    inFlightConversationId = null,
                    retryRequired = current.retryRequired || change.retryRequired,
                    continuationRequired =
                        current.continuationRequired || change.continuationRequired,
                )
            )
        }
        CollaborationRoomRecoveryChange.Finish ->
            AppResult.Success(CollaborationRoomRecoveryPosition())
    }
}

internal fun collaborationRoomRecoveryCompletion(
    progress: CollaborationRoomRecoveryProgress?
): CollaborationRoomRecoveryCompletion {
    val position = progress?.position ?: return CollaborationRoomRecoveryCompletion.Covered
    return when {
        position.retryRequired || position.inFlightConversationId != null ->
            CollaborationRoomRecoveryCompletion.Retry
        position.continuationRequired -> CollaborationRoomRecoveryCompletion.Continue
        else -> CollaborationRoomRecoveryCompletion.Covered
    }
}

private fun invalidRoomProgress() =
    collaborationSetupFailure("client.collaboration_invalid_room_recovery", FailureKind.Validation)
