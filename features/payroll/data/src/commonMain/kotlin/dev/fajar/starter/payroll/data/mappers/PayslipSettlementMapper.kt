package dev.fajar.starter.payroll.data.mappers

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.payroll.data.dto.PayslipSettlementDto
import dev.fajar.starter.payroll.domain.entities.PayslipSettlement

fun PayslipSettlementDto.toSettlement(): PayslipSettlement {
    require(assessmentRevision >= 0 && currency == "IDR")
    val entitled = payrollPaymentMinorUnits(entitlement)
    val paid = payrollPaymentMinorUnits(confirmedPaid)
    val recovered = payrollPaymentMinorUnits(confirmedRecovered)
    val balance = entitled - paid + recovered
    require(recovered <= paid)
    require(payrollPaymentMinorUnits(payable) == balance.coerceAtLeast(0))
    require(payrollPaymentMinorUnits(recoverable) == (-balance).coerceAtLeast(0))
    return PayslipSettlement(
        assessmentRevision,
        DecimalAmount.parse(entitlement),
        DecimalAmount.parse(confirmedPaid),
        DecimalAmount.parse(confirmedRecovered),
        DecimalAmount.parse(payable),
        DecimalAmount.parse(recoverable),
        currency,
    )
}

/** Validates the wire total's fourteen integral digits without binary floating-point rounding. */
internal fun payrollPaymentMinorUnits(amount: String): Long {
    require(paymentTotal.matches(amount))
    val whole = amount.substringBefore('.').toLong()
    val fraction = amount.substringAfter('.', "").padEnd(2, '0').toLong()
    return whole * 100 + fraction
}

private val paymentTotal = Regex("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,2})?")
