package dev.fajar.starter.leave.data

import dev.fajar.starter.leave.data.datasources.LeavePreparationDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlinx.datetime.LocalDate

class LeavePreparationDataFixture(val base: LeaveDataFixture = LeaveDataFixture()) :
    LeavePreparationDataSource {
    val selection =
        LeaveDraftSelection(
            base.employee.id,
            base.balance.typeId,
            listOf(
                LeaveRequestedDay(LocalDate(2026, 10, 15), LeavePortion.FULL),
                LeaveRequestedDay(LocalDate(2026, 10, 16), LeavePortion.FIRST_HALF),
                LeaveRequestedDay(LocalDate(2026, 10, 17), LeavePortion.FULL),
            ),
        )
    val type =
        LeaveTypeDto(
            base.balance.typeId,
            "ANNUAL",
            "Annual leave",
            "2026-01-01",
            true,
            true,
            false,
            true,
            0,
            0,
        )
    var page = LeavePageDto(listOf(type))
    var value =
        LeaveRequestPreviewDto(
            base.employee.id,
            base.now.toString(),
            "Asia/Jakarta",
            base.detail.policy,
            base.detail.days,
            "1.5",
            listOf("2026-10-17"),
            listOf(LeavePreviewBalanceDto(2026, "10.5", "1", "0.5", "1.5", "9", 3)),
        )
    var failure: Exception? = null
    var calls = 0

    override suspend fun types(
        sessionId: String,
        companyId: String,
        asOf: String,
        after: String?,
    ): LeavePageDto<LeaveTypeDto> {
        calls++
        failure?.let { throw it }
        return page
    }

    override suspend fun preview(
        sessionId: String,
        companyId: String,
        request: LeavePreviewRequest,
    ): LeaveRequestPreviewDto {
        calls++
        failure?.let { throw it }
        return value
    }
}
