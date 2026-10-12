@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.workforce.data.overtime.datasources.OvertimeRemoteDataSource
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeRepository
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteOvertimeRepository(private val remote: OvertimeRemoteDataSource) : OvertimeRepository {
    override suspend fun requests(
        access: WorkforceAccess,
        employeeId: String,
        period: CalendarPeriod,
        status: OvertimeStatus?,
        after: String?,
    ) = safeApiCall {
        listOf(access.companyId, employeeId).forEach { require(Uuid.parse(it).toString() == it) }
        remote
            .requests(
                access.sessionId,
                access.companyId,
                employeeId,
                period.from.toString(),
                period.until.toString(),
                status?.name,
                after,
            )
            .toRequests(employeeId, period, status, after)
    }

    override suspend fun details(access: WorkforceAccess, selection: OvertimeRequestSelection) =
        safeApiCall {
            listOf(access.companyId, selection.requestId, selection.employeeId).forEach {
                require(Uuid.parse(it).toString() == it)
            }
            remote
                .details(
                    access.sessionId,
                    access.companyId,
                    selection.requestId,
                    selection.historyAfter,
                )
                .toDetails(selection)
        }
}
