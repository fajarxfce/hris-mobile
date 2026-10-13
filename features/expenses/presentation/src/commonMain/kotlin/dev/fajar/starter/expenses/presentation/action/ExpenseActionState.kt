package dev.fajar.starter.expenses.presentation.action

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.presentation.detail.toLineItems
import dev.fajar.starter.expenses.presentation.expenseVerificationCodes

data class ExpenseActionState(
    val mode: ExpenseActionMode,
    val active: Boolean = false,
    val closing: Boolean = false,
    val content: ExpenseSnapshot<ExpenseView>? = null,
    val form: ExpenseActionForm? = null,
    val command: ExpenseCommandRecord? = null,
    val commandReady: Boolean = false,
    val loading: Boolean = true,
    val work: ExpenseActionWork? = null,
    val failure: Failure? = null,
    val restriction: Failure? = null,
    val dialog: ExpenseActionDialog? = null,
) {
    val lines
        get() = content?.value?.takeIf { restriction == null }?.toLineItems().orEmpty()

    val displayMode
        get() = command?.command?.payload?.toActionMode() ?: mode

    val visibleFailure
        get() = restriction ?: failure

    val needsVerification
        get() = visibleFailure?.code in expenseVerificationCodes

    val changed
        get() = form != null && content != null && form.version != content.value.claim.version

    val available
        get() =
            content?.let {
                restriction == null &&
                    admitExpenseEditing(it.access) is AppResult.Success &&
                    mode.action in availableExpenseActions(it.value)
            } == true

    val editable
        get() =
            active &&
                !closing &&
                available &&
                !changed &&
                commandReady &&
                command == null &&
                work == null &&
                !loading &&
                form != null

    val canConfirm
        get() = editable && dialog == null

    val canRecover
        get() =
            active && !closing && commandReady && restriction == null && work == null && !loading
}

enum class ExpenseActionWork {
    REFRESHING,
    SENDING,
    OPENING,
    DISCARDING,
}

enum class ExpenseActionDialog {
    EXIT,
    DISCARD_COMMAND,
}
