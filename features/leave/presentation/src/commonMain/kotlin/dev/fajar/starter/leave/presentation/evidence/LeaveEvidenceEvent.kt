package dev.fajar.starter.leave.presentation.evidence

import dev.fajar.starter.documents.domain.entities.DocumentEvidenceSnapshot

sealed interface LeaveEvidenceEvent {
    data object Entered : LeaveEvidenceEvent

    data object Stopped : LeaveEvidenceEvent

    data object ReloadRequested : LeaveEvidenceEvent

    data class PageRequested(val after: String?) : LeaveEvidenceEvent

    data class Selected(val revisionId: String) : LeaveEvidenceEvent

    data class Expired(val snapshot: DocumentEvidenceSnapshot) : LeaveEvidenceEvent

    data object Dismissed : LeaveEvidenceEvent

    data object VerificationRequested : LeaveEvidenceEvent
}
