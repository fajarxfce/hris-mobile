@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.data.mappers

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.payroll.data.dto.PayslipPaymentsDto
import dev.fajar.starter.payroll.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Current API responses must provide their independently versioned financial projection. */
fun PayslipPaymentsDto.toPayments(expectedId: String): PayslipPayments {
    requireNotNull(settlement)
    return toStoredPayments(expectedId)
}

/** Retained legacy facts remain readable; absent amounts stay unknown until reacquired. */
fun PayslipPaymentsDto.toStoredPayments(expectedId: String): PayslipPayments {
    require(
        Uuid.parse(assessmentId).toString() == assessmentId &&
            assessmentId == expectedId &&
            version >= 0 &&
            attempts.size <= 20
    )
    val settlement = settlement?.toSettlement()
    require(settlement == null || settlement.assessmentRevision <= version)
    val mapped =
        attempts.map {
            listOf(it.batchId, it.itemId).forEach { id -> require(Uuid.parse(id).toString() == id) }
            if (settlement == null) {
                require(it.amount == null && it.assessmentRevision == null)
            } else {
                val amount = requireNotNull(it.amount)
                require(amount.substringBefore('.').length <= 12)
                require(payrollPaymentMinorUnits(amount) > 0)
                require(requireNotNull(it.assessmentRevision) in 0..settlement.assessmentRevision)
            }
            PayslipPaymentAttempt(
                it.batchId,
                it.itemId,
                PayslipPaymentStatus.valueOf(it.status),
                Instant.parse(it.createdAt),
                it.releasedAt?.let(Instant::parse),
                it.resolvedAt?.let(Instant::parse),
                it.amount?.let(DecimalAmount::parse),
                it.assessmentRevision,
            )
        }
    require(mapped.map { it.itemId }.distinct().size == mapped.size)
    require(
        mapped.zipWithNext().all { (first, next) ->
            first.createdAt > next.createdAt ||
                (first.createdAt == next.createdAt && first.itemId > next.itemId)
        }
    )
    if (settlement != null) {
        val paid =
            mapped
                .filter { it.status == PayslipPaymentStatus.SUCCEEDED }
                .sumOf { payrollPaymentMinorUnits(requireNotNull(it.amount).value) }
        require(paid == payrollPaymentMinorUnits(settlement.confirmedPaid.value))
    }
    return PayslipPayments(assessmentId, version, mapped, settlement)
}
