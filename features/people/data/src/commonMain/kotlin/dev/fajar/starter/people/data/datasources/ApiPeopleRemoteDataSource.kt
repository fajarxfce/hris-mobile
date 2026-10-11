package dev.fajar.starter.people.data.datasources

import dev.fajar.starter.people.data.api.PeopleApi
import org.koin.core.annotation.Single

@Single
class ApiPeopleRemoteDataSource(private val api: PeopleApi) : PeopleRemoteDataSource {
    override suspend fun companyDirectory(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
    ) = api.companyDirectory(sessionId, companyId, query, after)

    override suspend fun selfEmployments(sessionId: String, companyId: String, after: String?) =
        api.selfEmployments(sessionId, companyId, after)

    override suspend fun employment(
        sessionId: String,
        companyId: String,
        employeeId: String,
        asOf: String,
    ) = api.employment(sessionId, companyId, employeeId, asOf)

    override suspend fun profile(sessionId: String, companyId: String, employeeId: String) =
        api.profile(sessionId, companyId, employeeId)
}
