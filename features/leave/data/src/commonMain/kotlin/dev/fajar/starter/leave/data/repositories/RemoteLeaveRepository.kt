@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.leave.data.datasources.LeaveRemoteDataSource
import dev.fajar.starter.leave.data.dto.LeaveActionRequest
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.LeaveActionRepository
import dev.fajar.starter.leave.domain.repositories.LeaveRecordRepository
import dev.fajar.starter.leave.domain.repositories.LeaveRepository
import dev.fajar.starter.leave.domain.repositories.LeaveSubmissionRepository
import dev.fajar.starter.network.safeApiCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteLeaveRepository(private val remote: LeaveRemoteDataSource) :
    LeaveRepository, LeaveRecordRepository, LeaveActionRepository, LeaveSubmissionRepository {
    override suspend fun request(access: LeaveAccess, id: String) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(id)
        remote.details(access.sessionId, access.companyId, id, null).toReplicaRequest(id)
    }

    override suspend fun balance(access: LeaveAccess, id: String) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(id)
        remote.balance(access.sessionId, access.companyId, id).toReplicaBalance(id)
    }

    override suspend fun submit(sessionId: String, command: LeaveSubmissionCommand) = safeApiCall {
        remote
            .submit(sessionId, command.companyId, command.id, command.toSubmissionRequest())
            .toSubmissionReceipt(command)
    }

    override suspend fun act(sessionId: String, command: LeaveCommand) = safeApiCall {
        listOf(command.id, command.companyId, command.requestId).forEach {
            require(Uuid.parse(it).toString() == it)
        }
        require(command.expectedVersion in 0 until 9_007_199_254_740_991L)
        require(command.reason.isNotBlank() && command.reason.length <= 1_000)
        remote
            .act(
                sessionId,
                command.companyId,
                command.requestId,
                command.id,
                when (command.action) {
                    LeaveRequestAction.WITHDRAW,
                    LeaveRequestAction.WITHDRAW_CANCELLATION -> "withdraw"
                    LeaveRequestAction.REQUEST_CANCELLATION -> "cancellation"
                },
                LeaveActionRequest(command.expectedVersion, command.reason),
            )
            .toReceipt(command)
    }

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
