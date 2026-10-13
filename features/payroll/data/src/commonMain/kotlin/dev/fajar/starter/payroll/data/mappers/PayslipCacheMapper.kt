package dev.fajar.starter.payroll.data.mappers

import dev.fajar.starter.payroll.data.dto.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.PAYSLIP_FRESHNESS_PERIOD
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

fun PayslipSummary.toDto() =
    PayslipSummaryDto(
        id,
        employeeId,
        employeeNumber,
        employeeName,
        month.toString(),
        plannedPaymentDate.toString(),
        currency,
        taxableGross.value,
        withheld.value,
        takeHome.value,
        publishedAt.toString(),
        0,
    )

fun PayslipOverview.toDto() =
    PayslipOverviewDto(
        requireNotNull(selection.year),
        selection.after,
        PayslipPageDto(page.items.map { it.toDto() }, page.nextCursor),
    )

fun PayslipOverviewDto.toOverview(): PayslipOverview {
    val selection = PayslipSelection(year, after)
    return PayslipOverview(selection, page.toPage(selection))
}

fun Payslip.toDto() =
    PayslipDto(
        summary.toDto(),
        companyCode,
        companyName,
        PayslipCalculationDto(
            breakdown.earnings.map {
                PayslipEarningDto(it.kind.name, it.code, it.name, it.amount.value, it.taxable)
            },
            breakdown.contributions.map {
                PayslipContributionDto(
                    it.program.name,
                    it.base.value,
                    it.employeeAmount.value,
                    it.employerAmount.value,
                )
            },
            PayslipTaxDto(
                breakdown.taxRule,
                summary.taxableGross.value,
                breakdown.taxAllowance.value,
                breakdown.deductionAllowance.value,
                summary.withheld.value,
                summary.takeHome.value,
            ),
            PayslipTaxInputDto(
                breakdown.retirementContributions.value,
                breakdown.qualifiedDonations.value,
                breakdown.otherDeductions.value,
                breakdown.nonCashTaxable.value,
            ),
            breakdown.employeeDeductions.value,
        ),
    )

fun PayslipPayments.toDto() =
    PayslipPaymentsDto(
        payslipId,
        version,
        attempts.map {
            PayslipPaymentAttemptDto(
                it.batchId,
                it.itemId,
                it.status.name,
                it.createdAt.toString(),
                it.releasedAt?.toString(),
                it.resolvedAt?.toString(),
            )
        },
    )

fun PayslipView.toDto() = PayslipViewDto(payslip.toDto(), payments.toDto())

fun PayslipViewDto.toView(): PayslipView {
    val id = payslip.summary.id
    return PayslipView(payslip.toPayslip(id), payments.toPayments(id))
}

fun <T, D> PayslipSnapshot<T>.toDto(mapValue: (T) -> D) =
    PayslipSnapshotDto(
        access.permissions.toSet(),
        access.sessionExpiresAt.toString(),
        access.timezone,
        fetchedAt.toString(),
        refreshAfter.toString(),
        mapValue(value),
    )

fun <T, D> PayslipSnapshotDto<D>.toSnapshot(
    access: PayrollAccess,
    generation: String,
    mapValue: (D) -> T,
): PayslipSnapshot<T> {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(timezone.length in 1..100)
    TimeZone.of(timezone)
    val owner =
        access.copy(
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
            timezone = timezone,
        )
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(expiry > fetched && expiry <= fetched + PAYSLIP_FRESHNESS_PERIOD)
    return PayslipSnapshot(owner, generation, mapValue(value), fetched, expiry)
}
