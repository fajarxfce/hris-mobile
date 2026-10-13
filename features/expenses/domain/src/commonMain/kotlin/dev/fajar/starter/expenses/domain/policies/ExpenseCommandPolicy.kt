@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val EXPENSE_AUTOMATIC_ATTEMPTS = 3
const val EXPENSE_MAXIMUM_ATTEMPTS = 1_000

fun ExpenseCommand.belongsTo(access: ExpenseAccess) =
    accountId == access.accountId && audience == access.audience && companyId == access.companyId

fun ExpenseCommand.slot(): ExpenseCommandSlot =
    if (payload is ExpenseCommandPayload.SaveDraft && payload.expectedVersion == null)
        ExpenseCommandSlot.Creation(employeeId)
    else ExpenseCommandSlot.Claim(claimId)

fun ExpenseCommand.expectedReceiptVersion(): Long =
    when (val value = payload) {
        is ExpenseCommandPayload.SaveDraft -> value.expectedVersion?.plus(1) ?: 0
        is ExpenseCommandPayload.Submit -> value.expectedVersion + 1
        is ExpenseCommandPayload.Cancel -> value.expectedVersion + 1
        is ExpenseCommandPayload.Withdraw -> value.expectedVersion + 1
    }

fun validateExpenseCommandSlot(slot: ExpenseCommandSlot): AppResult<Unit> {
    val id =
        when (slot) {
            is ExpenseCommandSlot.Creation -> slot.employeeId
            is ExpenseCommandSlot.Claim -> slot.claimId
        }
    return if (validateExpenseId(id) is AppResult.Success) AppResult.Success(Unit)
    else expenseFailure("client.expense_command_changed", FailureKind.Conflict)
}

fun admitExpenseCommandInsertion(admission: ExpenseCommandAdmission): AppResult<Unit> =
    when (admission) {
        ExpenseCommandAdmission.STORED,
        ExpenseCommandAdmission.OCCUPIED -> AppResult.Success(Unit)
        ExpenseCommandAdmission.CAPACITY_EXCEEDED ->
            expenseFailure("client.command_queue_full", FailureKind.Storage)
        ExpenseCommandAdmission.SCOPE_CHANGED ->
            expenseFailure("client.expense_scope_changed", FailureKind.AccessDenied)
    }

fun validateExpenseAction(
    snapshot: ExpenseSnapshot<ExpenseView>,
    access: ExpenseAccess,
    action: ExpenseRequestedAction,
    reason: String,
    now: Instant,
    submissionId: String,
): AppResult<ExpenseCommandPayload> {
    val allowed = admitExpenseEditing(access)
    if (allowed is AppResult.Failed) return allowed
    val lease = validateExpenseCache(access, snapshot, now)
    if (lease is AppResult.Failed) return lease
    val explanation = validateExpenseReason(reason)
    if (explanation is AppResult.Failed) return explanation
    val view = snapshot.value
    val claim = view.claim
    if (
        validateExpenseId(claim.id) is AppResult.Failed ||
            validateExpenseId(claim.employeeId) is AppResult.Failed ||
            claim.version !in 0 until 9_007_199_254_740_991L
    )
        return expenseFailure("client.invalid_response", FailureKind.Unexpected)
    return when (action) {
        ExpenseRequestedAction.SUBMIT ->
            when {
                claim.status != ExpenseStatus.DRAFT ->
                    expenseFailure("expense_not_editable", FailureKind.Conflict)
                claim.submissionCount >= 20 ->
                    expenseFailure("expense_submission_limit", FailureKind.Conflict)
                claim.draft.lines.isEmpty() -> expenseFailure("expense_lines_required")
                Uuid.parseOrNull(submissionId)?.toString() != submissionId ->
                    expenseFailure("invalid_expense_action")
                else -> AppResult.Success(ExpenseCommandPayload.Submit(claim.version, submissionId))
            }
        ExpenseRequestedAction.CANCEL ->
            if (claim.status !in setOf(ExpenseStatus.DRAFT, ExpenseStatus.RETURNED))
                expenseFailure("expense_not_editable", FailureKind.Conflict)
            else AppResult.Success(ExpenseCommandPayload.Cancel(claim.version))
        ExpenseRequestedAction.WITHDRAW -> {
            val current = view.submission
            if (
                claim.status != ExpenseStatus.PENDING ||
                    current == null ||
                    !current.current ||
                    claim.latestSubmissionId != current.id ||
                    current.claimId != claim.id ||
                    current.employeeId != claim.employeeId ||
                    current.claimVersion != claim.version ||
                    current.approval.status !in
                        setOf(ExpenseApprovalStatus.PENDING, ExpenseApprovalStatus.BLOCKED)
            )
                expenseFailure("expense_submission_not_current", FailureKind.Conflict)
            else AppResult.Success(ExpenseCommandPayload.Withdraw(claim.version, current.id))
        }
    }
}

/**
 * A generated submission ID is not a user edit; an already retained intent keeps its original ID.
 */
fun sameExpenseIntent(expected: ExpenseCommandPayload, stored: ExpenseCommandPayload): Boolean =
    if (expected is ExpenseCommandPayload.Submit && stored is ExpenseCommandPayload.Submit)
        expected.expectedVersion == stored.expectedVersion
    else expected == stored

/** A later rejection cannot disprove an earlier uncertain commit. */
fun resolveExpenseCommandFailure(
    previous: ExpenseCommandOutcome,
    failure: Failure,
): ExpenseCommandOutcome =
    if (previous !is ExpenseCommandOutcome.Unconfirmed && failure.code in definiteExpenseRejections)
        ExpenseCommandOutcome.Rejected(failure)
    else ExpenseCommandOutcome.Unconfirmed(failure)

private val definiteExpenseRejections =
    setOf(
        "invalid_expense_action",
        "invalid_expense_draft",
        "invalid_expense_line",
        "duplicate_expense_line",
        "expense_claim_amount_limit",
        "expense_lines_required",
        "expense_transaction_date_invalid",
        "expense_transaction_expired",
        "expense_line_limit_exceeded",
        "expense_category_limit_exceeded",
        "expense_cost_center_required",
        "expense_receipt_required",
        "expense_access_denied",
        "expense_identity_immutable",
        "expense_not_editable",
        "expense_draft_revision_limit",
        "expense_draft_capacity",
        "expense_category_unavailable",
        "expense_cost_center_unavailable",
        "expense_receipt_unavailable",
        "expense_receipt_not_ready",
        "expense_employment_ineligible",
        "expense_submission_limit",
        "expense_submission_not_current",
        "expense_approval_requires_split",
        "expense_claim_not_found",
        "expense_submission_not_found",
        "stale_version",
        "employee_not_found",
        "approval_unavailable",
        "approval_changed",
        "approval_template_not_found",
        "approval_group_too_large",
        "company_access_denied",
        "company_not_found",
        "sync_access_denied",
        "session_revoked",
        "access_denied",
        "forbidden",
        "mfa_required",
        "mfa_setup_required",
        "recent_authentication_required",
        "mfa_verification_required",
    )
