package dev.fajar.starter.expenses.presentation.draft

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes
import dev.fajar.starter.expenses.presentation.item.ExpenseItemTarget

data class ExpenseDraftState(
    val editing: Boolean = false,
    val closing: Boolean = false,
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
    val restriction: Failure? = null,
    val dialog: ExpenseDraftDialog? = null,
    val itemTarget: ExpenseItemTarget? = null,
) {
    val dirty: Boolean
        get() = form != null && form != original

    val editable: Boolean
        get() =
            active &&
                !closing &&
                commandReady &&
                command == null &&
                preparation != null &&
                restriction == null &&
                work == null &&
                !loading &&
                !remoteChanged

    val canSave: Boolean
        get() = editable && itemTarget == null

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
}

enum class ExpenseDraftDialog {
    EXIT,
    RELOAD,
    DISCARD_COMMAND,
}
