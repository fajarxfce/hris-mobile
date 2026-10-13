package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*

fun ExpenseSummary.toDto() =
    ExpenseSummaryDto(
        id,
        employeeId,
        employeeNumber,
        employeeName,
        title,
        total.value,
        currency,
        createdAt.toString(),
        status.name,
        version,
    )

fun ExpenseLine.toDto() =
    ExpenseLineDto(
        id,
        categoryId,
        occurredOn.toString(),
        amount.value,
        description,
        costCenterId,
        receiptIds.toList(),
    )

fun ExpenseDraft.toDto() =
    ExpenseDraftDto(
        revision,
        employeeNumber,
        employeeName,
        title,
        description,
        total.value,
        currency,
        lines.map { it.toDto() },
        actorId,
        reason,
        recordedAt.toString(),
    )

fun ExpenseClaim.toDto() =
    ExpenseClaimDto(
        id,
        employeeId,
        createdBy,
        createdAt.toString(),
        version,
        draft.revision,
        status.name,
        draft.toDto(),
        submissionCount,
        latestSubmissionId,
    )

fun ExpenseChange.toDto() =
    ExpenseChangeDto(
        version,
        draftRevision,
        status.name,
        kind.name,
        actorId,
        reason,
        recordedAt.toString(),
        submissionId,
    )

fun ExpenseReceipt.toDto() =
    ExpenseReceiptDto(id, fileName, mediaType, size, sha256, possibleDuplicate)

fun ExpenseSubmittedLine.toDto() =
    ExpenseSubmittedLineDto(
        id,
        occurredOn.toString(),
        amount.value,
        description,
        ExpenseCategoryReferenceDto(
            category.id,
            category.code,
            category.name,
            category.version,
            category.revision,
        ),
        costCenter?.let { ExpenseCostCenterDto(it.id, it.code, it.name, it.version) },
        receipts.map { it.toDto() },
    )

fun ExpenseApproval.toDto() =
    ExpenseApprovalDto(id, status.name, version, currentStep, stages.map { it.toSet() })

fun ExpenseReview.toDto() =
    ExpenseReviewDto(
        claimVersion,
        approvalVersion,
        step,
        actorId,
        decision.name,
        status.name,
        reason,
        decidedAt.toString(),
    )

fun ExpenseSubmission.toDto() =
    ExpenseSubmissionDto(
        id,
        claimId,
        employeeId,
        number,
        draftRevision,
        title,
        description,
        total.value,
        currency,
        lines.map { it.toDto() },
        submittedAt.toString(),
        reason,
        approval.toDto(),
        claimVersion,
        claimStatus.name,
        current,
        reviews.map { it.toDto() },
    )

fun ExpensePaymentAttempt.toDto() =
    ExpensePaymentAttemptDto(
        batchId,
        itemId,
        status.name,
        createdAt.toString(),
        releasedAt?.toString(),
        settledAt?.toString(),
    )
