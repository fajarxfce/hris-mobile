package dev.fajar.starter.expenses.presentation.formatters

import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.localization.AppString

fun ExpenseStatus.label(): AppString =
    when (this) {
        ExpenseStatus.DRAFT -> AppString.ExpenseDraft
        ExpenseStatus.PENDING -> AppString.ExpensePending
        ExpenseStatus.RETURNED -> AppString.ExpenseReturned
        ExpenseStatus.APPROVED -> AppString.ExpenseApproved
        ExpenseStatus.REJECTED -> AppString.ExpenseRejected
        ExpenseStatus.CANCELLED -> AppString.ExpenseCancelled
    }

fun ExpenseApprovalStatus.label(): AppString =
    when (this) {
        ExpenseApprovalStatus.PENDING -> AppString.ExpenseApprovalPending
        ExpenseApprovalStatus.BLOCKED -> AppString.ExpenseApprovalBlocked
        ExpenseApprovalStatus.APPROVED -> AppString.ExpenseApproved
        ExpenseApprovalStatus.REJECTED -> AppString.ExpenseRejected
        ExpenseApprovalStatus.CANCELLED -> AppString.ExpenseCancelled
    }

fun ExpenseDecision.label(): AppString =
    when (this) {
        ExpenseDecision.APPROVE -> AppString.ExpenseApproved
        ExpenseDecision.REJECT -> AppString.ExpenseRejected
        ExpenseDecision.RETURN -> AppString.ExpenseReturned
    }

fun ExpensePaymentStatus.label(): AppString =
    when (this) {
        ExpensePaymentStatus.PREPARED -> AppString.ExpensePaymentPrepared
        ExpensePaymentStatus.PENDING -> AppString.ExpensePaymentPending
        ExpensePaymentStatus.SUCCEEDED -> AppString.ExpensePaymentSucceeded
        ExpensePaymentStatus.FAILED -> AppString.ExpensePaymentFailed
        ExpensePaymentStatus.CANCELLED -> AppString.ExpensePaymentCancelled
    }

fun ExpenseChangeKind.label(): AppString =
    when (this) {
        ExpenseChangeKind.DRAFT_SAVED -> AppString.ExpenseDraftSaved
        ExpenseChangeKind.SUBMITTED -> AppString.ExpenseSent
        ExpenseChangeKind.WITHDRAWN -> AppString.ExpenseWithdrawn
        ExpenseChangeKind.REVIEWED -> AppString.ExpenseReviewed
        ExpenseChangeKind.CANCELLED -> AppString.ExpenseClaimCancelled
    }
