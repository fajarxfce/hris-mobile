package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.workforce.data.WorkforceDataFixture
import dev.fajar.starter.workforce.data.overtime.datasources.OvertimeRemoteDataSource
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection

class OvertimeDataFixture : OvertimeRemoteDataSource {
    val base = WorkforceDataFixture()
    val access = base.access.copy(permissions = setOf("overtime.self.manage"))
    val requested = OvertimeIntervalDto("2026-10-12T12:00:00Z", "2026-10-12T14:00:00Z", 0, 120)
    val request =
        OvertimeRequestDto(
            "66666666-6666-6666-6666-666666666666",
            base.employee.id,
            "E01",
            "Alex",
            access.accountId,
            access.accountId,
            base.now.toString(),
            "2026-10-12",
            "Asia/Jakarta",
            base.workDay,
            requested,
            "Inventory count",
            "PLANNED",
            null,
            null,
            null,
            null,
            0,
            null,
            0,
        )
    var page = OvertimePageDto(listOf(request))
    val planned =
        OvertimeChangeDto(
            0,
            "PLANNED",
            "PLANNED",
            null,
            0,
            null,
            access.accountId,
            base.now.toString(),
            request.reason,
        )
    var detail =
        OvertimeDetailsDto(
            request,
            null,
            OvertimePageDto(listOf(planned)),
            listOf("SUBMIT_ACTUAL", "WITHDRAW"),
        )
    val selection = OvertimeRequestSelection(request.id, base.employee.id)
    var failure: Exception? = null

    override suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ): OvertimePageDto<OvertimeRequestDto> {
        failure?.let { throw it }
        return page
    }

    override suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ): OvertimeDetailsDto {
        failure?.let { throw it }
        return detail
    }
}
