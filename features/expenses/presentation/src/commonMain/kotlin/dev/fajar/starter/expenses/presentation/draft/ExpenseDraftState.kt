package dev.fajar.starter.expenses.presentation.draft

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes
import dev.fajar.starter.expenses.presentation.item.ExpenseItemTarget
import dev.fajar.starter.expenses.presentation.working.ExpenseEditPersistence
import dev.fajar.starter.expenses.presentation.working.ExpenseEditorState

data class ExpenseDraftState(
    val editing: Boolean = false,
    override val closing: Boolean = false,
    val form: ExpenseDraftForm? = null,
    val original: ExpenseDraftForm? = null,
    val version: Long? = null,
    val preparation: ExpenseDraftPreparation? = null,
    val references: ExpenseReferenceValues? = null,
    val command: ExpenseCommandRecord? = null,
    val commandReady: Boolean = false,
    val active: Boolean = false,
    val loading: Boolean = false,
    val work: ExpenseDraftWork? = null,
    val remoteChanged: Boolean = false,
    val failure: Failure? = null,
    override val restriction: Failure? = null,
    val dialog: ExpenseDraftDialog? = null,
    val itemTarget: ExpenseItemTarget? = null,
    override val persistence: ExpenseEditPersistence? = null,
    val discardRevision: String? = null,
) : ExpenseEditorState {
    val dirty: Boolean
        get() = form != null && form != original

    val editable: Boolean
        get() =
            active &&
                !closing &&
                commandReady &&
                command == null &&
                persistence != null &&
                restriction == null &&
                work == null &&
                dialog == null &&
                !remoteChanged &&
                persistence.failure?.code != "client.expense_working_copy_changed"

    val canSave: Boolean
        get() = editable && itemTarget == null && persistence?.edited?.item == null

    val visibleFailure: Failure?
        get() = restriction ?: failure

    val needsVerification: Boolean
        get() = visibleFailure?.code in expenseVerificationCodes
}

enum class ExpenseDraftWork {
    SAVING,
    SENDING,
    OPENING,
    DISCARDING,
    LEAVING,
    OPENING_ITEM,
}

enum class ExpenseDraftDialog {
    EXIT,
    RELOAD,
    DISCARD_COMMAND,
    DISCARD_ITEM,
    LEAVE_UNSAVED,
}
