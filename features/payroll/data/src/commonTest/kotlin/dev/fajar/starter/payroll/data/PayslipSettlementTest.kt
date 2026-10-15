package dev.fajar.starter.payroll.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.data.dto.*
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.data.repositories.RemotePayslipRepository
import dev.fajar.starter.payroll.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class PayslipSettlementTest {
    @Test
    fun revisedEntitlementRetainsTheOriginalTransferAndExactOutstandingBalance() = runTest {
        val f = PayslipDataFixture()
        f.payments =
            f.payments.copy(
                version = 5,
                settlement =
                    f.settlement.copy(
                        assessmentRevision = 2,
                        entitlement = "11000000.75",
                        confirmedPaid = "10000000.75",
                        payable = "1000000.00",
                    ),
                attempts = listOf(f.attempt.copy(status = "SUCCEEDED", amount = "10000000.75")),
            )
        val value =
            assertIs<AppResult.Success<PayslipPayments>>(
                    RemotePayslipRepository(f).payments(f.access, f.summary.id)
                )
                .value
        assertEquals(5L, value.version)
        assertEquals(2L, value.settlement!!.assessmentRevision)
        assertEquals(0L, value.attempts.single().assessmentRevision)
        assertEquals("1000000", value.settlement!!.payable.value)
        assertEquals("10000000.75", value.attempts.single().amount!!.value)
        assertEquals(value, value.toDto().toPayments(f.summary.id))
        assertFalse(value.toString().contains("1000000"))
        assertFalse(value.settlement.toString().contains("11000000"))
        assertFalse(value.attempts.single().toString().contains("10000000"))
    }

    @Test
    fun confirmedReturnsReduceAnOverpaymentWithoutErasingPaidHistory() = runTest {
        val f = PayslipDataFixture()
        f.payments =
            f.payments.copy(
                version = 4,
                settlement =
                    f.settlement.copy(
                        assessmentRevision = 1,
                        entitlement = "9000000.75",
                        confirmedPaid = "10000000.75",
                        confirmedRecovered = "400000.25",
                        payable = "0",
                        recoverable = "599999.75",
                    ),
                attempts = listOf(f.attempt.copy(status = "SUCCEEDED", amount = "10000000.75")),
            )
        val value =
            assertIs<AppResult.Success<PayslipPayments>>(
                    RemotePayslipRepository(f).payments(f.access, f.summary.id)
                )
                .value
        assertEquals("599999.75", value.settlement!!.recoverable.value)
        assertEquals("400000.25", value.settlement!!.confirmedRecovered.value)
        assertEquals("10000000.75", value.settlement!!.confirmedPaid.value)
        assertEquals(PayslipPaymentStatus.SUCCEEDED, value.attempts.single().status)
    }

    @Test
    fun totalsBeyondBinaryDoublePrecisionRetainEveryMinorUnit() = runTest {
        val f = PayslipDataFixture()
        f.payments =
            f.payments.copy(
                settlement =
                    f.settlement.copy(
                        entitlement = "90071992547409.93",
                        payable = "90071992547409.93",
                    ),
                attempts = emptyList(),
            )
        val value =
            assertIs<AppResult.Success<PayslipPayments>>(
                    RemotePayslipRepository(f).payments(f.access, f.summary.id)
                )
                .value
        assertEquals("90071992547409.93", value.settlement!!.payable.value)
        f.payments =
            f.payments.copy(
                settlement = f.payments.settlement!!.copy(payable = "90071992547409.92")
            )
        assertIs<AppResult.Failed>(RemotePayslipRepository(f).payments(f.access, f.summary.id))
    }

    @Test
    fun currentResponsesRejectMissingOrIncoherentFinancialFacts() = runTest {
        val f = PayslipDataFixture()
        val valid = f.payments
        val invalid =
            listOf(
                valid.copy(settlement = null),
                valid.copy(settlement = f.settlement.copy(assessmentRevision = -1)),
                valid.copy(settlement = f.settlement.copy(assessmentRevision = 2)),
                valid.copy(settlement = f.settlement.copy(currency = "USD")),
                valid.copy(settlement = f.settlement.copy(payable = "10232500.01")),
                valid.copy(settlement = f.settlement.copy(recoverable = "1")),
                valid.copy(
                    settlement = f.settlement.copy(confirmedRecovered = "1", payable = "10232501")
                ),
                valid.copy(
                    settlement = f.settlement.copy(confirmedPaid = "1", payable = "10232499")
                ),
                valid.copy(attempts = listOf(f.attempt.copy(status = "SUCCEEDED"))),
                valid.copy(attempts = listOf(f.attempt.copy(amount = null))),
                valid.copy(attempts = listOf(f.attempt.copy(assessmentRevision = null))),
                valid.copy(attempts = listOf(f.attempt.copy(assessmentRevision = -1))),
                valid.copy(attempts = listOf(f.attempt.copy(assessmentRevision = 1))),
                valid.copy(attempts = listOf(f.attempt.copy(amount = "0"))),
                valid.copy(attempts = listOf(f.attempt.copy(amount = "1000000000000"))),
            )
        for (raw in invalid) {
            f.payments = raw
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        RemotePayslipRepository(f).payments(f.access, f.summary.id)
                    )
                    .failure
                    .kind,
            )
        }
        for (amount in listOf("NaN", "1e5", "-1", "00", "0.001", "100000000000000")) {
            f.payments =
                valid.copy(settlement = f.settlement.copy(entitlement = amount, payable = amount))
            assertIs<AppResult.Failed>(RemotePayslipRepository(f).payments(f.access, f.summary.id))
        }
    }

    @Test
    fun legacyRecordsKeepKnownStatusesAndUnknownAmountsWithoutInventingBalances() {
        val f = PayslipDataFixture()
        val raw =
            Json.decodeFromString<PayslipPaymentsDto>(
                """{"assessmentId":"${f.summary.id}","version":1,"attempts":[{"batchId":"${f.attempt.batchId}","itemId":"${f.attempt.itemId}","status":"PENDING","createdAt":"${f.now}","releasedAt":"${f.now}","resolvedAt":null}]}"""
            )
        val saved = raw.toStoredPayments(f.summary.id)
        assertNull(saved.settlement)
        assertNull(saved.attempts.single().amount)
        assertNull(saved.attempts.single().assessmentRevision)
        assertEquals(PayslipPaymentStatus.PENDING, saved.attempts.single().status)
        assertEquals(saved, saved.toDto().toStoredPayments(f.summary.id))
        assertFailsWith<IllegalArgumentException> { raw.toPayments(f.summary.id) }
        assertFailsWith<IllegalArgumentException> {
            raw.copy(attempts = listOf(f.attempt)).toStoredPayments(f.summary.id)
        }
    }

    @Test
    fun summaryRoundTripRetainsItsRevisionAndDoesNotBorrowThePaymentRevision() {
        val f = PayslipDataFixture()
        val summary = f.summary.copy(version = 3).toSummary()
        assertEquals(3L, summary.version)
        assertEquals(summary, summary.toDto().toSummary())
        val view =
            PayslipView(
                f.detail.copy(summary = f.summary.copy(version = 3)).toPayslip(f.summary.id),
                f.payments.toPayments(f.summary.id),
            )
        assertEquals(3L, view.payslip.summary.version)
        assertEquals(0L, view.payments.settlement!!.assessmentRevision)
        assertEquals(view, view.toDto().toView())
    }
}
