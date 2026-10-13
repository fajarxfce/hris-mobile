package dev.fajar.starter.expenses.presentation.action

import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.commands.canDiscard

internal fun ExpenseActionState.withSnapshot(
    snapshot: ExpenseSnapshot<ExpenseView>?
): ExpenseActionState =
    copy(
        content = snapshot,
        form =
            if (snapshot == null || mode.action == null) form
            else
                when {
                    form == null -> ExpenseActionForm(snapshot.value.claim.version)
                    form.reason.isBlank() && work != ExpenseActionWork.SENDING ->
                        form.copy(version = snapshot.value.claim.version)
                    else -> form
                },
    )

internal fun ExpenseActionState.withCommand(record: ExpenseCommandRecord?): ExpenseActionState =
    copy(
        command = record,
        commandReady = true,
        dialog =
            dialog.takeUnless {
                it == ExpenseActionDialog.DISCARD_COMMAND && record?.canDiscard() != true
            },
    )
