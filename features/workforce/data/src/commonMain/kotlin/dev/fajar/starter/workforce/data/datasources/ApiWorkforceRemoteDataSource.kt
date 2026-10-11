package dev.fajar.starter.workforce.data.datasources

import dev.fajar.starter.workforce.data.api.WorkforceApi
import org.koin.core.annotation.Single

@Single
class ApiWorkforceRemoteDataSource(private val api: WorkforceApi) : WorkforceRemoteDataSource {
    override suspend fun selfEmployments(sessionId: String, companyId: String, after: String?) =
        api.selfEmployments(sessionId, companyId, after)

    override suspend fun calendar(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
    ) = api.calendar(sessionId, companyId, employeeId, from, until)
}
