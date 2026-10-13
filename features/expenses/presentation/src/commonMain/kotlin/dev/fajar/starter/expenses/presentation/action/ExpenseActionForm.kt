package dev.fajar.starter.expenses.presentation.action

data class ExpenseActionForm(val version: Long, val reason: String = "") {
    override fun toString() = "ExpenseActionForm(version=$version)"
}
