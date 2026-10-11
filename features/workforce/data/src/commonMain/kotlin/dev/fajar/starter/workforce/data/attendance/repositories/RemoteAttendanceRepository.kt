@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.attendance.repositories

import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.workforce.data.attendance.datasources.AttendanceRemoteDataSource
import dev.fajar.starter.workforce.data.attendance.dto.AttendanceWindowRequest
import dev.fajar.starter.workforce.data.attendance.mappers.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceRepository
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

@Single
class RemoteAttendanceRepository(private val remote: AttendanceRemoteDataSource) :
    AttendanceRepository {
    override suspend fun days(access: WorkforceAccess, employeeId: String, period: CalendarPeriod) =
        safeApiCall {
            Uuid.parse(access.companyId)
            Uuid.parse(employeeId)
            remote
                .days(
                    access.sessionId,
                    access.companyId,
                    employeeId,
                    period.from.toString(),
                    period.until.toString(),
                )
                .toAttendanceDays(period)
        }

    override suspend fun window(
        access: WorkforceAccess,
        employeeId: String,
        deviceId: String,
        operationId: String,
    ) = safeApiCall {
        Uuid.parse(access.companyId)
        Uuid.parse(employeeId)
        Uuid.parse(operationId)
        remote
            .window(
                access.sessionId,
                access.companyId,
                employeeId,
                operationId,
                AttendanceWindowRequest(deviceId),
            )
            .toWindow(employeeId, deviceId)
    }

    override suspend fun record(access: WorkforceAccess, command: AttendanceCommand) = safeApiCall {
        require(command.belongsTo(access))
        remote
            .record(access.sessionId, command.companyId, command.employeeId, command.toCaptureDto())
            .toReceipt(command)
    }
}
