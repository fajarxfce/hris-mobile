package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.validateExpenseReceipt
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

fun ExpenseReceiptDto.toReceipt(): ExpenseReceipt {
    return ExpenseReceipt(
            expenseId(revisionId),
            fileName,
            mediaType,
            size,
            sha256,
            possibleDuplicate,
        )
        .also { require(validateExpenseReceipt(it) is AppResult.Success) }
}

fun ExpenseSubmittedLineDto.toSubmittedLine(): ExpenseSubmittedLine {
    require(description.isNotBlank() && description.length <= 500 && receipts.size <= 3)
    require(category.code.isNotBlank() && category.code.length <= 32)
    require(category.name.isNotBlank() && category.name.length <= 200)
    val date = LocalDate.parse(occurredOn)
    require(date.year in 1900..2200)
    val documents = receipts.map { it.toReceipt() }
    require(documents.distinctBy { it.id }.size == documents.size)
    val center =
        costCenter?.let {
            require(
                it.code.isNotBlank() &&
                    it.code.length <= 32 &&
                    it.name.isNotBlank() &&
                    it.name.length <= 200
            )
            ExpenseCostCenter(expenseId(it.id), it.code, it.name, expenseVersion(it.version))
        }
    return ExpenseSubmittedLine(
        expenseId(id),
        date,
        expenseAmount(amount),
        description,
        ExpenseCategoryReference(
            expenseId(category.id),
            category.code,
            category.name,
            expenseVersion(category.version),
            expenseVersion(category.appliedRevision),
        ),
        center,
        documents.toList(),
    )
}

fun ExpenseApprovalDto.toApproval(): ExpenseApproval {
    require(stages.size in 1..20 && currentStep in stages.indices && stages.all { it.size <= 500 })
    return ExpenseApproval(
        expenseId(id),
        ExpenseApprovalStatus.valueOf(status),
        expenseVersion(version),
        currentStep,
        stages.map { stage -> stage.map(::expenseId).toSet() },
    )
}

fun ExpenseReviewDto.toReview(): ExpenseReview {
    require(step in 0..19 && reason.length <= 1000)
    return ExpenseReview(
        expenseVersion(claimVersion),
        expenseVersion(approvalVersion),
        step,
        expenseId(actorId),
        ExpenseDecision.valueOf(decision),
        ExpenseStatus.valueOf(status),
        reason,
        Instant.parse(decidedAt),
    )
}

fun ExpenseSubmissionDto.toSubmission(expectedId: String): ExpenseSubmission {
    require(
        expenseId(id) == expectedId &&
            number in 1..20 &&
            draftRevision in 0..99 &&
            currency == "IDR"
    )
    require(
        title.isNotBlank() &&
            title.length <= 160 &&
            description.length <= 2000 &&
            reason.length <= 1000
    )
    require(lines.size in 1..20 && reviews.size <= 20)
    val submitted = lines.map { it.toSubmittedLine() }
    require(submitted.distinctBy { it.id }.size == submitted.size)
    val routing = approval.toApproval()
    val decisions = reviews.map { it.toReview() }
    require(decisions.all { it.step in routing.stages.indices && it.claimVersion <= claimVersion })
    require(decisions.distinctBy { it.claimVersion }.size == decisions.size)
    return ExpenseSubmission(
        id,
        expenseId(claimId),
        expenseId(employmentId),
        number,
        draftRevision,
        title,
        description,
        expenseAmount(totalAmount),
        currency,
        submitted.toList(),
        Instant.parse(submittedAt),
        reason,
        routing,
        expenseVersion(claimVersion),
        ExpenseStatus.valueOf(claimStatus),
        current,
        decisions.toList(),
    )
}
