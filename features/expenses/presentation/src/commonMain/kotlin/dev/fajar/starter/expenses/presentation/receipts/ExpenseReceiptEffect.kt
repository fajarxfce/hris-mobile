package dev.fajar.starter.expenses.presentation.receipts

import dev.fajar.starter.documents.domain.entities.DocumentEvidence

sealed interface ExpenseReceiptEffect {
    data class Selected(val document: DocumentEvidence) : ExpenseReceiptEffect

    data object Dismiss : ExpenseReceiptEffect

    data object VerifyAccount : ExpenseReceiptEffect

    data object RecheckWorkspace : ExpenseReceiptEffect
}
