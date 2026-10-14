package dev.fajar.starter.expenses.presentation.working

import dev.fajar.starter.common.result.Failure

interface ExpenseEditorState {
    val persistence: ExpenseEditPersistence?
    val restriction: Failure?
    val closing: Boolean
}
