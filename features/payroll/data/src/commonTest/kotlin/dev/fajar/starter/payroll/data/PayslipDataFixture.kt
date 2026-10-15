package dev.fajar.starter.payroll.data

import dev.fajar.starter.fileio.Sha256Digest
import dev.fajar.starter.payroll.data.datasources.PayrollRemoteDataSource
import dev.fajar.starter.payroll.data.dto.*
import dev.fajar.starter.payroll.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class PayslipDataFixture(val pdfRevision: Long = 0) : PayrollRemoteDataSource {
    val now = Instant.parse("2026-10-13T00:00:00Z")
    val access =
        PayrollAccess(
            "session",
            "11111111-1111-1111-1111-111111111111",
            "https://hris.example.test/",
            "22222222-2222-2222-2222-222222222222",
            "Asia/Jakarta",
            setOf("payroll.self.read"),
            now + 24.hours,
            now + 1.hours,
            "client.workspace_expired",
        )
    val summary =
        PayslipSummaryDto(
            "44444444-4444-4444-4444-444444444444",
            "33333333-3333-3333-3333-333333333333",
            "E01",
            "Alex",
            "2026-09",
            "2026-09-28",
            "IDR",
            "10500000.0000",
            "157500.0000",
            "10232500.0000",
            now.toString(),
            0,
        )
    var page = PayslipPageDto(listOf(summary), null)
    val earning = PayslipEarningDto("BASIC", "BASIC", "Basic pay", "10500000", true)
    val calculation =
        PayslipCalculationDto(
            listOf(earning),
            emptyList(),
            PayslipTaxDto(
                "pph21-ter-2026",
                summary.taxableGross,
                "0",
                "0",
                summary.withheld,
                summary.takeHome,
            ),
            PayslipTaxInputDto("0", "0", "0", "0"),
            "110000",
        )
    var detail = PayslipDto(summary, "CO", "Company", calculation)
    val attempt =
        PayslipPaymentAttemptDto(
            "55555555-5555-5555-5555-555555555555",
            "66666666-6666-6666-6666-666666666666",
            "PENDING",
            now.toString(),
            now.toString(),
            null,
            "10232500",
            0,
        )
    val settlement = PayslipSettlementDto(0, "10232500", "0", "0", "10232500", "0", "IDR")
    var payments = PayslipPaymentsDto(summary.id, 1, listOf(attempt), settlement)
    val bytes = "%PDF-1.4\nfixture\n%%EOF".encodeToByteArray()
    val metadata =
        PayslipPdfMetadata(
            summary.id,
            PayslipDocumentLanguage.ID,
            bytes.size.toLong(),
            "\"${Sha256Digest().apply { update(bytes) }.finishHex()}\"",
            pdfRevision,
        )
    var headers =
        PayslipPdfHeadersDto(
            listOf(metadata.entityTag),
            listOf(metadata.size.toString()),
            listOf("application/pdf"),
            emptyList(),
        )
    var downloadCall: suspend () -> ByteArray = { bytes }
    var failure: Exception? = null

    override suspend fun payslips(
        sessionId: String,
        companyId: String,
        from: String,
        until: String,
        after: String?,
    ): PayslipPageDto {
        failure?.let { throw it }
        return page
    }

    override suspend fun payslip(sessionId: String, companyId: String, id: String): PayslipDto {
        failure?.let { throw it }
        return detail
    }

    override suspend fun payments(
        sessionId: String,
        companyId: String,
        id: String,
    ): PayslipPaymentsDto {
        failure?.let { throw it }
        return payments
    }

    override suspend fun inspectPdf(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long,
        language: String,
    ): PayslipPdfHeadersDto {
        failure?.let { throw it }
        return headers
    }

    override suspend fun downloadPdf(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long,
        language: String,
        entityTag: String,
        size: Long,
    ): ByteArray {
        failure?.let { throw it }
        return downloadCall()
    }
}
