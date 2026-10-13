package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

private val lineAmount = Regex("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?")

fun validateExpenseLineInput(input: ExpenseLineInput, id: String): AppResult<ExpenseLine> {
    if (input.amount.length > 64 || input.occurredOn.length != 10 || input.description.length > 500)
        return expenseFailure("invalid_expense_line")
    val amount = input.amount.trim()
    val description = input.description.trim()
    if (
        validateExpenseId(id) is AppResult.Failed ||
            validateExpenseId(input.categoryId) is AppResult.Failed ||
            (input.costCenterId != null &&
                validateExpenseId(input.costCenterId) is AppResult.Failed) ||
            input.receiptRevisionIds.size > 3 ||
            input.receiptRevisionIds.distinct().size != input.receiptRevisionIds.size ||
            input.receiptRevisionIds.any { validateExpenseId(it) is AppResult.Failed } ||
            description.isBlank() ||
            description.length > 500 ||
            !lineAmount.matches(amount)
    )
        return expenseFailure("invalid_expense_line")
    val date =
        try {
            LocalDate.parse(input.occurredOn)
        } catch (_: IllegalArgumentException) {
            return expenseFailure("invalid_expense_line")
        }
    if (date.year !in 1900..2200 || date.toString() != input.occurredOn)
        return expenseFailure("invalid_expense_line")
    val exact = DecimalAmount.parse(amount)
    val minor = expenseMinorUnits(exact)
    if (minor == null || minor !in 1..99_999_999_999_999L)
        return expenseFailure("invalid_expense_line")
    return AppResult.Success(
        ExpenseLine(
            id,
            input.categoryId,
            date,
            exact,
            description,
            input.costCenterId,
            input.receiptRevisionIds.toList(),
        )
    )
}

fun normalizeExpenseDraft(
    input: ExpenseDraftInput,
    expectedVersion: Long?,
): AppResult<ExpenseCommandPayload.SaveDraft> {
    if (input.title.length > 160 || input.description.length > 2_000)
        return expenseFailure("invalid_expense_draft")
    val title = input.title.trim()
    val description = input.description.trim()
    if (
        title.isBlank() ||
            title.length > 160 ||
            description.length > 2_000 ||
            input.lines.size > 20 ||
            expectedVersion != null && expectedVersion !in 0 until 9_007_199_254_740_991L
    )
        return expenseFailure("invalid_expense_draft")
    if (input.lines.distinctBy { it.id }.size != input.lines.size)
        return expenseFailure("duplicate_expense_line")
    val lines = mutableListOf<ExpenseLine>()
    var total = 0L
    for (line in input.lines) {
        val validated =
            validateExpenseLineInput(
                ExpenseLineInput(
                    line.id,
                    line.categoryId,
                    line.occurredOn.toString(),
                    line.amount.value,
                    line.description,
                    line.costCenterId,
                    line.receiptIds,
                ),
                line.id,
            )
        when (validated) {
            is AppResult.Failed -> return validated
            is AppResult.Success -> {
                lines.add(validated.value)
                total += requireNotNull(expenseMinorUnits(validated.value.amount))
            }
        }
    }
    if (total > 99_999_999_999_999L) return expenseFailure("expense_claim_amount_limit")
    return AppResult.Success(
        ExpenseCommandPayload.SaveDraft(expectedVersion, title, description, lines.toList())
    )
}

fun validateExpenseReason(reason: String): AppResult<Unit> =
    if (reason.isBlank() || reason.length > 1_000) expenseFailure("invalid_expense_action")
    else AppResult.Success(Unit)

fun validateExpenseDraftPreparation(
    prepared: ExpenseDraftPreparation,
    access: ExpenseAccess,
    now: Instant,
): AppResult<Unit> {
    val admission = admitExpenseEditing(access)
    if (admission is AppResult.Failed) return admission
    if (!sameExpenseScope(prepared.access, access))
        return expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
    if (
        prepared.fetchedAt > now ||
            now >= minOf(prepared.expiresAt, access.readUntil, access.sessionExpiresAt)
    )
        return expenseFailure("client.expense_preparation_expired", FailureKind.AccessDenied)
    if (
        prepared.claim != null &&
            (prepared.claim.employeeId != prepared.employee.id ||
                prepared.claim.status !in setOf(ExpenseStatus.DRAFT, ExpenseStatus.RETURNED))
    )
        return expenseFailure("expense_not_editable", FailureKind.Conflict)
    if (prepared.claim?.draft?.revision == 99)
        return expenseFailure("expense_draft_revision_limit", FailureKind.Conflict)
    return AppResult.Success(Unit)
}
