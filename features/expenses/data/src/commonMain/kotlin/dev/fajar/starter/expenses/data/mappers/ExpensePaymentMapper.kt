package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import kotlin.time.Instant

fun ExpenseClaimPaymentsDto.toClaimPayments(expectedId: String): ExpenseClaimPayments {
    require(expenseId(claimId) == expectedId && version in 0L..60L)
    val values = attempts.toPayments()
    require(version in values.size.toLong()..(values.size * 3L))
    return ExpenseClaimPayments(claimId, version, values)
}

fun List<ExpensePaymentAttemptDto>.toPayments(): List<ExpensePaymentAttempt> {
    require(size <= 20)
    val payments = map {
        val status = ExpensePaymentStatus.valueOf(it.status)
        val created = Instant.parse(it.createdAt)
        val released = it.releasedAt?.let(Instant::parse)
        val settled = it.settledAt?.let(Instant::parse)
        if (status == ExpensePaymentStatus.SUCCEEDED) require(released != null && settled != null)
        ExpensePaymentAttempt(
            expenseId(it.batchId),
            expenseId(it.itemId),
            status,
            created,
            released,
            settled,
        )
    }
    require(payments.distinctBy { it.itemId }.size == payments.size)
    return payments.toList()
}
