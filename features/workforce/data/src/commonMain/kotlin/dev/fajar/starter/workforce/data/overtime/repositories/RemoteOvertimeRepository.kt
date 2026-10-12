@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.workforce.data.overtime.datasources.OvertimeRemoteDataSource
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteOvertimeRepository(private val remote: OvertimeRemoteDataSource) :
    OvertimeRepository, OvertimePlanningRepository, OvertimeSubmissionRepository {
    override suspend fun send(sessionId: String, command: OvertimeCommand) = safeApiCall {
        require(command.toDto().toCommand() == command)
        val receipt =
            when (val payload = command.payload) {
                is OvertimeCommandPayload.Plan ->
                    remote.plan(
                        sessionId,
                        command.companyId,
                        command.id,
                        OvertimePlanRequest(
                            command.requestId,
                            command.employeeId,
                            payload.expectedEmploymentVersion,
                            command.workDate.toString(),
                            payload.requested.toRequest(),
                            command.reason,
                            payload.planningRevision,
                        ),
                    )
                is OvertimeCommandPayload.Actual ->
                    remote.actual(
                        sessionId,
                        command.companyId,
                        command.requestId,
                        command.id,
                        OvertimeActualRequest(
                            payload.expectedVersion,
                            payload.actual.toRequest(),
                            command.reason,
                        ),
                    )
                is OvertimeCommandPayload.Withdrawal ->
                    remote.withdraw(
                        sessionId,
                        command.companyId,
                        command.requestId,
                        command.id,
                        OvertimeWithdrawalRequest(payload.expectedVersion, command.reason),
                    )
            }
        receipt.toReceipt(command)
    }

    override suspend fun review(access: WorkforceAccess, selection: OvertimePlanningSelection) =
        safeApiCall {
            listOf(access.companyId, selection.employeeId).forEach {
                require(Uuid.parse(it).toString() == it)
            }
            remote
                .review(
                    access.sessionId,
                    access.companyId,
                    selection.employeeId,
                    selection.workDate.toString(),
                )
                .toReview(selection)
        }

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
