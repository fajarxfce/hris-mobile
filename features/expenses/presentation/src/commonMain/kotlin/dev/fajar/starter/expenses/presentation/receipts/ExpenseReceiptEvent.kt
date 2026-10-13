package dev.fajar.starter.expenses.presentation.receipts

import dev.fajar.starter.documents.domain.entities.DocumentEvidenceSnapshot

sealed interface ExpenseReceiptEvent {
    data object Entered : ExpenseReceiptEvent

    data object Stopped : ExpenseReceiptEvent

    data object ReloadRequested : ExpenseReceiptEvent

    data class PageRequested(val after: String?) : ExpenseReceiptEvent

    data class Selected(val revisionId: String) : ExpenseReceiptEvent

    data class Expired(val snapshot: DocumentEvidenceSnapshot) : ExpenseReceiptEvent

    data object Dismissed : ExpenseReceiptEvent

    data object VerificationRequested : ExpenseReceiptEvent
}
