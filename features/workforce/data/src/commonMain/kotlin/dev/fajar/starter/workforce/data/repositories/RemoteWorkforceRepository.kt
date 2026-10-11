@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.workforce.data.datasources.WorkforceRemoteDataSource
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.repositories.WorkforceRepository
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteWorkforceRepository(private val remote: WorkforceRemoteDataSource) :
    WorkforceRepository {
    override suspend fun selfEmployments(access: WorkforceAccess, after: String?) = safeApiCall {
        Uuid.parse(access.companyId)
        remote.selfEmployments(access.sessionId, access.companyId, after).toDirectory(access, after)
    }

    override suspend fun calendar(
        access: WorkforceAccess,
        employeeId: String,
        period: CalendarPeriod,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(employeeId)
        remote
            .calendar(
                access.sessionId,
                access.companyId,
                employeeId,
                period.from.toString(),
                period.until.toString(),
            )
            .toCalendar(period)
    }
}
