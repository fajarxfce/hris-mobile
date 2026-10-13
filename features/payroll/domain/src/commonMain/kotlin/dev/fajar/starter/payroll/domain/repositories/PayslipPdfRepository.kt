package dev.fajar.starter.payroll.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.*

interface PayslipPdfRepository {
    suspend fun inspect(
        access: PayrollAccess,
        id: String,
        language: PayslipDocumentLanguage,
    ): AppResult<PayslipPdfMetadata>

    /** Success transfers ownership of the bounded plaintext buffer; the caller clears it. */
    suspend fun download(access: PayrollAccess, metadata: PayslipPdfMetadata): AppResult<ByteArray>
}
