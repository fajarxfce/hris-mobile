package dev.fajar.starter.leave.presentation.evidence

import dev.fajar.starter.documents.domain.entities.DocumentEvidence

sealed interface LeaveEvidenceEffect {
    data class Selected(val document: DocumentEvidence) : LeaveEvidenceEffect

    data object Dismiss : LeaveEvidenceEffect

    data object VerifyAccount : LeaveEvidenceEffect

    data object RecheckWorkspace : LeaveEvidenceEffect
}
