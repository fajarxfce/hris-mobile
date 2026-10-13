package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.documents.domain.entities.DocumentEvidence
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.receipts.ExpenseReceiptTarget

sealed interface ExpenseItemEvent {
    data object Entered : ExpenseItemEvent

    data object Stopped : ExpenseItemEvent

    data object ReloadRequested : ExpenseItemEvent

    data object DateOpened : ExpenseItemEvent

    data class DateMonthChanged(val delta: Int) : ExpenseItemEvent

    data class DateSelected(val day: Int) : ExpenseItemEvent

    data object CategoryOpened : ExpenseItemEvent

    data class CategorySelected(val id: String) : ExpenseItemEvent

    data class CategoryPageRequested(val after: String?) : ExpenseItemEvent

    data class CategoriesExpired(val value: ExpenseCategories) : ExpenseItemEvent

    data object CostCenterOpened : ExpenseItemEvent

    data class CostCenterQueryChanged(val value: String) : ExpenseItemEvent

    data class CostCenterSelected(val id: String?) : ExpenseItemEvent

    data class CostCenterPageRequested(val after: String?) : ExpenseItemEvent

    data class CostCentersExpired(val value: ExpenseCostCenters) : ExpenseItemEvent

    data class AmountChanged(val value: String) : ExpenseItemEvent

    data class DescriptionChanged(val value: String) : ExpenseItemEvent

    data object ReceiptOpened : ExpenseItemEvent

    data class ReceiptSelected(val target: ExpenseReceiptTarget, val value: DocumentEvidence) :
        ExpenseItemEvent

    data class ReceiptDismissed(val target: ExpenseReceiptTarget) : ExpenseItemEvent

    data class ReceiptRemoved(val id: String) : ExpenseItemEvent

    data object SaveRequested : ExpenseItemEvent

    data object DialogDismissed : ExpenseItemEvent

    data object BackRequested : ExpenseItemEvent

    data object ExitConfirmed : ExpenseItemEvent

    data object VerificationRequested : ExpenseItemEvent
}
