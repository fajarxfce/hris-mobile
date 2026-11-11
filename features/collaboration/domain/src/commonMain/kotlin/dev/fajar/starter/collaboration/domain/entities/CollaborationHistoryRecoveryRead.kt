package dev.fajar.starter.collaboration.domain.entities

/** Local finite-pass evidence; no content, reference or global completion is implied. */
sealed interface CollaborationHistoryRecoveryRead {
    data object Absent : CollaborationHistoryRecoveryRead

    data class Complete(val progress: CollaborationHistoryProgress) :
        CollaborationHistoryRecoveryRead

    data class Pending(val references: CollaborationHistoryReferences) :
        CollaborationHistoryRecoveryRead
}
