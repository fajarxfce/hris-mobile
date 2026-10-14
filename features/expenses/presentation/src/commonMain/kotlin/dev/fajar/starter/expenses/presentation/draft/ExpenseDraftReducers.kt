package dev.fajar.starter.expenses.presentation.draft

import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.working.entities.ExpenseWorkingCopy
import dev.fajar.starter.expenses.presentation.working.ExpenseEditPersistence

internal fun ExpenseDraftState.withPreparation(value: ExpenseDraftPreparation): ExpenseDraftState {
    if (persistence != null)
        return copy(
            preparation = value,
            failure = null,
            remoteChanged = persistence.edited.expectedVersion != value.claim?.version,
        )
    if (command != null) return copy(preparation = value, restriction = null, failure = null)
    val updated = value.claim?.draft.toForm()
    val changed = form != null && version != value.claim?.version && dirty
    return if (form == null || (!dirty && version != value.claim?.version))
        copy(
            form = updated,
            original = updated,
            version = value.claim?.version,
            preparation = value,
            restriction = null,
            failure = null,
            remoteChanged = false,
        )
    else copy(preparation = value, restriction = null, failure = null, remoteChanged = changed)
}

internal fun ExpenseDraftState.withWorkingCopy(value: ExpenseWorkingCopy): ExpenseDraftState {
    val input = value.input
    val form = ExpenseDraftForm(input.title, input.description, input.lines, input.reason)
    return copy(
        form = form,
        original = original ?: form,
        version = value.expectedVersion,
        references = value.references,
        persistence = ExpenseEditPersistence(value),
        restriction = null,
        failure = null,
    )
}
