@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.people.data.datasources.PeopleRemoteDataSource
import dev.fajar.starter.people.data.mappers.*
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import org.koin.core.annotation.Single

@Single
class RemotePeopleRepository(private val remote: PeopleRemoteDataSource) : PeopleRepository {
    override suspend fun selfEmployments(sessionId: String, companyId: String, after: String?) =
        safeApiCall {
            Uuid.parse(companyId)
            remote.selfEmployments(sessionId, companyId, after).toDirectory(companyId, after)
        }

    override suspend fun employment(
        sessionId: String,
        companyId: String,
        employeeId: String,
        asOf: LocalDate,
    ) = safeApiCall {
        Uuid.parse(companyId)
        Uuid.parse(employeeId)
        remote
            .employment(sessionId, companyId, employeeId, asOf.toString())
            .toDetails(companyId, employeeId, asOf)
    }

    override suspend fun profile(sessionId: String, companyId: String, employeeId: String) =
        safeApiCall {
            Uuid.parse(companyId)
            Uuid.parse(employeeId)
            remote.profile(sessionId, companyId, employeeId).toProfile()
        }
}
