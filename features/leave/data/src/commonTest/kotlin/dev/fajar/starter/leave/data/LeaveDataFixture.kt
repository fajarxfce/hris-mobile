package dev.fajar.starter.leave.data

import dev.fajar.starter.leave.data.datasources.LeaveRemoteDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class LeaveDataFixture : LeaveRemoteDataSource {
    val now = Instant.parse("2026-10-12T01:00:00Z")
    val access =
        LeaveAccess(
            "session",
            "11111111-1111-1111-1111-111111111111",
            "https://hris.example.test/",
            "22222222-2222-2222-2222-222222222222",
            setOf("leave.self.manage"),
            now + 24.hours,
            now + 1.hours,
            "client.workspace_expired",
        )
    val employee = LeaveEmploymentDto("33333333-3333-3333-3333-333333333333", "E01", "ACTIVE", 0)
    var directory =
        LeaveDirectoryDto(
            access.accountId,
            access.companyId,
            "2026-10-12",
            "Asia/Jakarta",
            LeavePageDto(listOf(employee)),
        )
    val balance =
        LeaveBalanceSummaryDto(
            "55555555-5555-5555-5555-555555555555",
            "ANNUAL",
            "Annual leave",
            LeaveBalanceDto(
                2026,
                "10.5",
                "1",
                "0.5",
                3,
                false,
                "44444444-4444-4444-4444-444444444444",
            ),
        )
    var balances =
        LeaveBalancesDto(
            LeaveEmployeeReferenceDto(employee.id),
            2026,
            LeavePageDto(listOf(balance)),
        )
    val request =
        LeaveRequestSummaryDto(
            "66666666-6666-6666-6666-666666666666",
            employee.id,
            "ANNUAL",
            "Annual leave",
            "2026-10-15",
            "2026-10-16",
            "1.5",
            "PENDING",
            "2026-10-12T01:00:00Z",
            0,
        )
    var requests = LeavePageDto(listOf(request))
    var detail =
        LeaveRequestDto(
            id = request.id,
            employeeId = employee.id,
            employeeNumber = employee.employeeNumber,
            employeeName = "Alex",
            ownerAccountId = access.accountId,
            submittedAt = now.toString(),
            policy =
                LeaveRequestPolicyDto(
                    balance.typeId,
                    "ANNUAL",
                    0,
                    "Annual leave",
                    true,
                    true,
                    false,
                ),
            days =
                listOf(
                    LeaveDayDto(
                        "2026-10-15",
                        "FULL",
                        "1",
                        "2026-10-15T01:00:00Z",
                        "2026-10-15T10:00:00Z",
                        480,
                        480,
                    ),
                    LeaveDayDto(
                        "2026-10-16",
                        "FIRST_HALF",
                        "0.5",
                        "2026-10-16T01:00:00Z",
                        "2026-10-16T10:00:00Z",
                        480,
                        240,
                    ),
                ),
            chargedDays = "1.5",
            reason = "Family appointment",
            status = "PENDING",
            version = 0,
            approval = LeaveWorkflowDto("88888888-8888-8888-8888-888888888888", "PENDING", 0, 0),
            history =
                LeavePageDto(
                    listOf(
                        LeaveChangeDto(
                            0,
                            "SUBMITTED",
                            "PENDING",
                            now.toString(),
                            "Family appointment",
                        )
                    )
                ),
            availableActions = setOf("WITHDRAW"),
            attachments =
                listOf(
                    LeaveAttachmentDto(
                        "99999999-9999-9999-9999-999999999999",
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "note.pdf",
                        "application/pdf",
                        640,
                        "a".repeat(64),
                    )
                ),
        )
    var failure: Exception? = null

    override suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ): LeaveRequestDto {
        failure?.let { throw it }
        return detail
    }

    override suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): LeaveDirectoryDto {
        failure?.let { throw it }
        return directory
    }

    override suspend fun balances(
        sessionId: String,
        companyId: String,
        employeeId: String,
        year: Int,
        after: String?,
    ): LeaveBalancesDto {
        failure?.let { throw it }
        return balances
    }

    override suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        status: String?,
        after: String?,
    ): LeavePageDto<LeaveRequestSummaryDto> {
        failure?.let { throw it }
        return requests
    }
}
