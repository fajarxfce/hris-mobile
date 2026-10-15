package dev.fajar.starter.payroll.data.datasources

import dev.fajar.starter.payroll.data.api.PayrollApi
import org.koin.core.annotation.Single

@Single
class ApiPayrollDataSource(private val api: PayrollApi) : PayrollRemoteDataSource {
    override suspend fun payslips(
        sessionId: String,
        companyId: String,
        from: String,
        until: String,
        after: String?,
    ) = api.payslips(sessionId, companyId, from, until, after)

    override suspend fun payslip(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long?,
    ) = api.payslip(sessionId, companyId, id, revision)

    override suspend fun payments(sessionId: String, companyId: String, id: String) =
        api.payments(sessionId, companyId, id)

    override suspend fun inspectPdf(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long,
        language: String,
    ) = api.inspectPdf(sessionId, companyId, id, revision, language)

    override suspend fun downloadPdf(
        sessionId: String,
        companyId: String,
        id: String,
        revision: Long,
        language: String,
        entityTag: String,
        size: Long,
    ) = api.downloadPdf(sessionId, companyId, id, revision, language, entityTag, size)
}
