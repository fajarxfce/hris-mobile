package dev.fajar.starter.payroll.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.data.dto.*
import dev.fajar.starter.payroll.data.repositories.RemotePayslipRepository
import dev.fajar.starter.payroll.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class PayslipMappingTest {
    @Test
    fun exactPublishedAmountsIncludingNegativeWithholdingAreNotRecalculated() = runTest {
        val f = PayslipDataFixture()
        val summary =
            f.summary.copy(
                taxableGross = "9007199254740993.12",
                withheld = "-120.05",
                takeHome = "9007199254741113.17",
            )
        f.detail =
            f.detail.copy(
                summary = summary,
                calculation =
                    f.calculation.copy(
                        tax =
                            f.calculation.tax.copy(
                                taxableGross = summary.taxableGross,
                                withheld = summary.withheld,
                                takeHome = summary.takeHome,
                            )
                    ),
            )
        val value =
            assertIs<AppResult.Success<Payslip>>(
                    RemotePayslipRepository(f).details(f.access, summary.id)
                )
                .value
        assertEquals(summary.taxableGross, value.summary.taxableGross.value)
        assertEquals(summary.withheld, value.summary.withheld.value)
        assertEquals(summary.takeHome, value.summary.takeHome.value)
    }

    @Test
    fun pagesRejectDuplicatesForeignYearsAndNonAdvancingCursors() = runTest {
        val f = PayslipDataFixture()
        val repo = RemotePayslipRepository(f)
        for (page in
            listOf(
                PayslipPageDto(listOf(f.summary, f.summary), null),
                PayslipPageDto(listOf(f.summary.copy(taxMonth = "2025-09")), null),
                PayslipPageDto(List(21) { f.summary }, null),
                PayslipPageDto(listOf(f.summary), "2026-09:${f.summary.id}"),
            )) {
            f.page = page
            assertIs<AppResult.Failed>(repo.list(f.access, PayslipSelection(2026)))
        }
        f.page = PayslipPageDto(listOf(f.summary), null)
        assertIs<AppResult.Failed>(
            repo.list(f.access, PayslipSelection(2026, "2026-09:${f.summary.id}"))
        )
        f.page =
            PayslipPageDto(
                List(20) {
                    f.summary.copy(
                        id = "44444444-4444-4444-4444-${(it + 1).toString().padStart(12, '0')}"
                    )
                },
                "2026-09:44444444-4444-4444-4444-000000000020",
            )
        val page =
            assertIs<AppResult.Success<PayslipPage>>(repo.list(f.access, PayslipSelection(2026)))
                .value
        assertEquals(20, page.items.size)
        assertEquals(f.page.nextCursor, page.nextCursor)
    }

    @Test
    fun summaryAndBreakdownMustDescribeTheSamePublishedAssessment() = runTest {
        val f = PayslipDataFixture()
        val original = f.detail
        val repo = RemotePayslipRepository(f)
        for (value in
            listOf(
                original.copy(
                    summary = f.summary.copy(id = "55555555-5555-5555-5555-555555555555")
                ),
                original.copy(summary = f.summary.copy(version = -1)),
                original.copy(summary = f.summary.copy(currency = "USD")),
                original.copy(
                    calculation = f.calculation.copy(tax = f.calculation.tax.copy(withheld = "NaN"))
                ),
                original.copy(
                    calculation = f.calculation.copy(tax = f.calculation.tax.copy(takeHome = "1"))
                ),
                original.copy(
                    calculation = f.calculation.copy(earnings = listOf(f.earning, f.earning))
                ),
                original.copy(
                    calculation =
                        f.calculation.copy(earnings = listOf(f.earning.copy(kind = "UNKNOWN")))
                ),
            )) {
            f.detail = value
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repo.details(f.access, f.summary.id)).failure.kind,
            )
        }
    }

    @Test
    fun paymentAttemptsKeepIndependentVersionAndRejectDuplicatedOrUnorderedHistory() = runTest {
        val f = PayslipDataFixture()
        val repo = RemotePayslipRepository(f)
        val valid =
            assertIs<AppResult.Success<PayslipPayments>>(repo.payments(f.access, f.summary.id))
                .value
        assertEquals(1L, valid.version)
        assertEquals(PayslipPaymentStatus.PENDING, valid.attempts.single().status)
        for (value in
            listOf(
                f.payments.copy(assessmentId = "55555555-5555-5555-5555-555555555555"),
                f.payments.copy(version = -1),
                f.payments.copy(attempts = listOf(f.attempt, f.attempt)),
                f.payments.copy(
                    attempts =
                        listOf(
                            f.attempt,
                            f.attempt.copy(itemId = "77777777-7777-7777-7777-777777777777"),
                        )
                ),
                f.payments.copy(attempts = listOf(f.attempt.copy(status = "PAID"))),
            )) {
            f.payments = value
            assertIs<AppResult.Failed>(repo.payments(f.access, f.summary.id))
        }
    }
}
