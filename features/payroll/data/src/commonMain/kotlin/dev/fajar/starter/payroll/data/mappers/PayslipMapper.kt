@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.data.mappers

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.data.dto.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun PayslipSummaryDto.toSummary(): PayslipSummary {
    listOf(id, employeeId).forEach { require(Uuid.parse(it).toString() == it) }
    require(version >= 0 && currency == "IDR")
    require(employeeNumber.isNotBlank() && employeeNumber.length <= 64)
    require(employeeName.isNotBlank() && employeeName.length <= 200)
    val month = YearMonth.parse(taxMonth)
    val paymentDate = LocalDate.parse(plannedPaymentDate)
    require(
        month.year in 2024..2100 && month.toString() == taxMonth && paymentDate.year in 2024..2101
    )
    return PayslipSummary(
        id,
        employeeId,
        employeeNumber,
        employeeName,
        month,
        paymentDate,
        currency,
        DecimalAmount.parse(taxableGross),
        DecimalAmount.parse(withheld),
        DecimalAmount.parse(takeHome),
        Instant.parse(publishedAt),
        version,
    )
}

fun PayslipPageDto.toPage(selection: PayslipSelection): PayslipPage {
    require(validatePayslipSelection(selection) is AppResult.Success && selection.year != null)
    require(items.size <= PAYSLIP_PAGE_SIZE)
    val mapped = items.map { it.toSummary() }
    require(
        mapped.map { it.id }.distinct().size == mapped.size &&
            mapped.all { it.month.year == selection.year }
    )
    require(
        mapped.zipWithNext().all { (first, next) ->
            first.month > next.month || (first.month == next.month && first.id < next.id)
        }
    )
    selection.after?.let { after ->
        val month = YearMonth.parse(after.take(7))
        val id = after.drop(8)
        require(mapped.all { it.month < month || (it.month == month && it.id > id) })
    }
    if (nextCursor != null) {
        require(mapped.size == PAYSLIP_PAGE_SIZE)
        val last = mapped.last()
        require(nextCursor == "${last.month}:${last.id}" && nextCursor != selection.after)
    }
    return PayslipPage(mapped, nextCursor)
}

fun PayslipDto.toPayslip(expectedId: String): Payslip {
    val summary = summary.toSummary()
    require(summary.id == expectedId && companyCode.isNotBlank() && companyCode.length <= 64)
    require(companyName.isNotBlank() && companyName.length <= 200)
    val tax = calculation.tax
    require(
        DecimalAmount.parse(tax.taxableGross) == summary.taxableGross &&
            DecimalAmount.parse(tax.withheld) == summary.withheld &&
            DecimalAmount.parse(tax.takeHome) == summary.takeHome
    )
    require(tax.ruleId.isNotBlank() && tax.ruleId.length <= 128)
    require(calculation.earnings.size <= 63 && calculation.contributions.size <= 5)
    val earnings =
        calculation.earnings.map {
            require(
                it.code.isNotBlank() &&
                    it.code.length <= 64 &&
                    (it.name == null || (it.name.isNotBlank() && it.name.length <= 200))
            )
            PayslipEarning(
                PayslipEarningKind.valueOf(it.kind),
                it.code,
                it.name,
                DecimalAmount.parse(it.amount),
                it.taxable,
            )
        }
    require(earnings.map { it.kind to it.code }.distinct().size == earnings.size)
    val contributions =
        calculation.contributions.map {
            PayslipContribution(
                PayslipInsuranceProgram.valueOf(it.program),
                DecimalAmount.parse(it.base),
                DecimalAmount.parse(it.employeeAmount),
                DecimalAmount.parse(it.employerAmount),
            )
        }
    require(contributions.map { it.program }.distinct().size == contributions.size)
    return Payslip(
        summary,
        companyCode,
        companyName,
        PayslipBreakdown(
            earnings,
            contributions,
            DecimalAmount.parse(tax.taxAllowance),
            DecimalAmount.parse(tax.deductionAllowance),
            DecimalAmount.parse(calculation.taxInput.retirementContributions),
            DecimalAmount.parse(calculation.taxInput.qualifiedDonations),
            DecimalAmount.parse(calculation.taxInput.otherNetDeductions),
            DecimalAmount.parse(calculation.employeeDeductions),
            DecimalAmount.parse(calculation.taxInput.nonCashTaxable),
            tax.ruleId,
        ),
    )
}
