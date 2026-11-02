package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session

fun collaborationRecoveryAccess(
    session: Session?,
    audience: String,
): AppResult<CollaborationRecoveryAccess> {
    if (
        session == null ||
            audience.length !in 1..2048 ||
            session.binding.audience != audience ||
            session.id.length !in 1..128 ||
            session.id.any { it.code !in 33..126 } ||
            !validCollaborationMessageId(session.user.id)
    )
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    // Cursor ownership is local; expired transport credentials do not erase retained progress.
    return AppResult.Success(CollaborationRecoveryAccess(session.id, audience, session.user.id))
}

fun validCollaborationRecoverySweep(sweep: CollaborationRecoverySweep): Boolean =
    validCollaborationMessageId(sweep.wakeGeneration) &&
        (sweep.afterCompanyId == null || validCollaborationMessageId(sweep.afterCompanyId)) &&
        (sweep.inFlightCompanyId == null || sweep.inFlightCompanyId == sweep.afterCompanyId)

fun validCollaborationRecoveryProgress(progress: CollaborationRecoveryProgress): Boolean =
    validCollaborationMessageId(progress.generation) &&
        (progress.sweep == null || validCollaborationRecoverySweep(progress.sweep))

internal fun nextCollaborationRecoverySweep(
    progress: CollaborationRecoveryProgress?,
    change: CollaborationRecoveryChange,
): AppResult<CollaborationRecoverySweep?> {
    if (progress != null && !validCollaborationRecoveryProgress(progress)) return invalidProgress()
    val current = progress?.sweep
    return when (change) {
        is CollaborationRecoveryChange.Begin -> {
            if (!validCollaborationMessageId(change.wake.generation)) return invalidProgress()
            // New wakes never reset a finite sweep already progressing toward higher company IDs.
            AppResult.Success(current ?: CollaborationRecoverySweep(change.wake.generation))
        }
        is CollaborationRecoveryChange.Attempt -> {
            if (
                current == null ||
                    !validCollaborationMessageId(change.companyId) ||
                    current.afterCompanyId?.let { change.companyId <= it } == true
            )
                return invalidProgress()
            AppResult.Success(
                current.copy(
                    afterCompanyId = change.companyId,
                    inFlightCompanyId = change.companyId,
                    retryRequired = current.retryRequired || current.inFlightCompanyId != null,
                )
            )
        }
        is CollaborationRecoveryChange.Complete -> {
            if (
                current == null ||
                    current.inFlightCompanyId != change.companyId ||
                    !validCollaborationMessageId(change.companyId)
            )
                return invalidProgress()
            AppResult.Success(
                current.copy(
                    inFlightCompanyId = null,
                    retryRequired = current.retryRequired || change.retryRequired,
                    continuationRequired =
                        current.continuationRequired || change.continuationRequired,
                )
            )
        }
        CollaborationRecoveryChange.Finish -> {
            if (current == null) return invalidProgress()
            AppResult.Success(
                if (
                    current.retryRequired ||
                        current.continuationRequired ||
                        current.inFlightCompanyId != null
                )
                    CollaborationRecoverySweep(current.wakeGeneration)
                else null
            )
        }
    }
}

private fun invalidProgress() =
    collaborationRecoveryFailure(
        "client.collaboration_invalid_recovery_progress",
        FailureKind.Validation,
    )
