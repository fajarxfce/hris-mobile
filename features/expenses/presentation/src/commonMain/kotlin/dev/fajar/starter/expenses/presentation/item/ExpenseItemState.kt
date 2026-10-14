package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes
import dev.fajar.starter.expenses.presentation.receipts.ExpenseReceiptTarget
import dev.fajar.starter.expenses.presentation.working.ExpenseEditPersistence
import dev.fajar.starter.expenses.presentation.working.ExpenseEditorState
import kotlinx.datetime.LocalDate

data class ExpenseItemState(
    val form: ExpenseItemForm,
    val original: ExpenseItemForm,
    val active: Boolean = false,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val choicesLoading: Boolean = false,
    override val closing: Boolean = false,
    val categoryPolicy: ExpenseCategory? = null,
    val categories: ExpenseCategories? = null,
    val costCenters: ExpenseCostCenters? = null,
    val centerQuery: String = "",
    val dialog: ExpenseItemDialog? = null,
    val evidenceTarget: ExpenseReceiptTarget? = null,
    val failure: Failure? = null,
    override val restriction: Failure? = null,
    override val persistence: ExpenseEditPersistence? = null,
    val finishing: Boolean = false,
) : ExpenseEditorState {
    val editable: Boolean
        get() =
            active &&
                ready &&
                !closing &&
                !finishing &&
                restriction == null &&
                persistence?.edited?.item != null &&
                persistence?.failure?.code != "client.expense_working_copy_changed"

    val canSave: Boolean
        get() = editable && !loading && dialog == null && evidenceTarget == null

    val dirty: Boolean
        get() = form.toInput() != original.toInput()

    val visibleFailure: Failure?
        get() = restriction ?: failure

    val needsVerification: Boolean
        get() = visibleFailure?.code in expenseVerificationCodes
}

sealed interface ExpenseItemDialog {
    data class Date(val month: LocalDate) : ExpenseItemDialog

    data object Categories : ExpenseItemDialog

    data object CostCenters : ExpenseItemDialog

    data object Exit : ExpenseItemDialog
}
