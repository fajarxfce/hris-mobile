package dev.fajar.starter.expenses.presentation.draft

import dev.fajar.starter.expenses.domain.entities.*

data class ExpenseDraftForm(
    val title: String = "",
    val description: String = "",
    val lines: List<ExpenseLine> = emptyList(),
    val reason: String = "",
) {
    override fun toString() = "ExpenseDraftForm(redacted)"
}

internal fun ExpenseDraft?.toForm() =
    if (this == null) ExpenseDraftForm() else ExpenseDraftForm(title, description, lines.toList())
