package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** An exact Company event at provider EOF can be checked against its local pending pass. */
fun canConfirmCollaborationOriginalIncomingCoverage(
    source: CollaborationHistorySource,
    eventId: String,
): AppResult<Boolean> {
    if (!validCollaborationEventId(eventId)) return invalidOriginalCoverage()
    val progress = source.progress
    if (progress == null)
        return if (source.reference == null) AppResult.Success(false) else invalidOriginalCoverage()
    if (
        !validCollaborationHistoryProgress(progress) ||
            progress.provider != null ||
            progress.incoming == null
    )
        return invalidOriginalCoverage()
    val reference = source.reference ?: return AppResult.Success(false)
    if (
        reference.event.eventId != eventId ||
            !validCollaborationProviderEvent(reference.event) ||
            reference.sequence !in 0 until progress.references
    )
        return invalidOriginalCoverage()
    return AppResult.Success(progress.exhausted)
}

/** Coverage is local evidence only; it cannot confer native authority or copy Company progress. */
fun confirmCollaborationOriginalIncomingCoverage(
    source: CollaborationHistorySource,
    eventId: String,
    pending: CollaborationHistoryReferences,
    confirmedProgress: CollaborationHistoryProgress?,
): AppResult<Boolean> {
    val candidate = canConfirmCollaborationOriginalIncomingCoverage(source, eventId)
    if (candidate is AppResult.Failed) return candidate
    if (!(candidate as AppResult.Success).value) return AppResult.Success(false)
    val progress = requireNotNull(source.progress)
    val incoming = requireNotNull(progress.incoming)
    val reference = requireNotNull(source.reference)
    if (
        pending.progress != progress ||
            confirmedProgress != progress ||
            !validCollaborationPendingHistoryReferences(pending, null, 1)
    )
        return invalidOriginalCoverage()
    if (pending.events.isEmpty() && !reference.resolved) return invalidOriginalCoverage()
    return AppResult.Success(
        pending.events.isEmpty() &&
            incoming.pendingAfter == null &&
            incoming.pendingThrough == null &&
            !incoming.pendingRetryRequired
    )
}

private fun invalidOriginalCoverage() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_request_changed",
        FailureKind.Conflict,
    )
