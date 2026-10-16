package dev.fajar.starter.leave.presentation.create

import dev.fajar.starter.documents.domain.entities.DocumentEvidence
import dev.fajar.starter.leave.domain.entities.LeavePortion
import dev.fajar.starter.leave.domain.entities.LeavePreparation
import dev.fajar.starter.leave.presentation.evidence.LeaveEvidenceTarget

sealed interface LeaveCreateEvent {
    data object Entered : LeaveCreateEvent

    data object Stopped : LeaveCreateEvent

    data object ReloadRequested : LeaveCreateEvent

    data object SaveRetryRequested : LeaveCreateEvent

    data object ReloadDraftRequested : LeaveCreateEvent

    data object ReloadDraftConfirmed : LeaveCreateEvent

    data object DiscardDraftRequested : LeaveCreateEvent

    data object DiscardDraftConfirmed : LeaveCreateEvent

    data object ExitWithoutSaving : LeaveCreateEvent

    data object TypePickerRequested : LeaveCreateEvent

    data class TypePageRequested(val after: String?) : LeaveCreateEvent

    data class TypeSelected(val id: String) : LeaveCreateEvent

    data class DatePickerRequested(val field: LeaveDateField) : LeaveCreateEvent

    data class DateMonthRequested(val delta: Int) : LeaveCreateEvent

    data class DateSelected(val day: Int) : LeaveCreateEvent

    data object PortionPickerRequested : LeaveCreateEvent

    data class PortionSelected(val portion: LeavePortion) : LeaveCreateEvent

    data class ReasonChanged(val value: String) : LeaveCreateEvent

    data object EvidencePickerRequested : LeaveCreateEvent

    data class EvidenceSelected(val target: LeaveEvidenceTarget, val document: DocumentEvidence) :
        LeaveCreateEvent

    data class EvidenceDismissed(val target: LeaveEvidenceTarget) : LeaveCreateEvent

    data class AttachmentRemoved(val revisionId: String) : LeaveCreateEvent

    data object PreviewRequested : LeaveCreateEvent

    data class PreviewExpired(val preparation: LeavePreparation) : LeaveCreateEvent

    data object SubmitRequested : LeaveCreateEvent

    data object RetryRequested : LeaveCreateEvent

    data object OpenRequestRequested : LeaveCreateEvent

    data object DiscardRequested : LeaveCreateEvent

    data object DialogDismissed : LeaveCreateEvent

    data object BackRequested : LeaveCreateEvent

    data object VerificationRequested : LeaveCreateEvent
}
