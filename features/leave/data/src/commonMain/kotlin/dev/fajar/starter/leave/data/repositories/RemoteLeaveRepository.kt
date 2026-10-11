@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.leave.data.datasources.LeaveRemoteDataSource
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.LeaveRepository
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteLeaveRepository(private val remote: LeaveRemoteDataSource) : LeaveRepository {
    override suspend fun details(access: LeaveAccess, selection: LeaveRequestSelection) =
        safeApiCall {
            Uuid.parse(access.companyId)
            Uuid.parse(selection.requestId)
            remote
                .details(
                    access.sessionId,
                    access.companyId,
                    selection.requestId,
                    selection.historyAfter,
                )
                .toDetails(selection)
        }

    override suspend fun selfEmployments(access: LeaveAccess, after: String?) = safeApiCall {
        Uuid.parse(access.companyId)
        remote.selfEmployments(access.sessionId, access.companyId, after).toDirectory(access, after)
    }

    override suspend fun balances(
        access: LeaveAccess,
        employeeId: String,
        year: Int,
        after: String?,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(employeeId)
        remote
            .balances(access.sessionId, access.companyId, employeeId, year, after)
            .toBalances(employeeId, year, after)
    }

    override suspend fun requests(
        access: LeaveAccess,
        employeeId: String,
        status: LeaveStatus?,
        after: String?,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(employeeId)
        remote
            .requests(access.sessionId, access.companyId, employeeId, status?.name, after)
            .toRequests(employeeId, status, after)
    }
}
