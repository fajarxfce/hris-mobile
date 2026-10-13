package dev.fajar.starter.payroll.data.datasources

import dev.fajar.starter.payroll.data.dto.*

interface PayrollRemoteDataSource {
    suspend fun payslips(
        sessionId: String,
        companyId: String,
        from: String,
        until: String,
        after: String?,
    ): PayslipPageDto

    suspend fun payslip(sessionId: String, companyId: String, id: String): PayslipDto

    suspend fun payments(sessionId: String, companyId: String, id: String): PayslipPaymentsDto

    suspend fun inspectPdf(
        sessionId: String,
        companyId: String,
        id: String,
        language: String,
    ): PayslipPdfHeadersDto

    suspend fun downloadPdf(
        sessionId: String,
        companyId: String,
        id: String,
        language: String,
        entityTag: String,
        size: Long,
    ): ByteArray
}
