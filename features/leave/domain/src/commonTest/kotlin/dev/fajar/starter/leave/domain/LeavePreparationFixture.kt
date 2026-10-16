package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.LeavePreparationRepository
import dev.fajar.starter.leave.domain.usecases.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class LeavePreparationFixture(val base: LeaveFixture = LeaveFixture()) {
    val selected =
        LeaveDraftSelection(
            base.employee.id,
            base.balance.typeId,
            listOf(LeaveRequestedDay(LocalDate(2026, 10, 15), LeavePortion.FIRST_HALF)),
        )
    val type =
        LeaveTypeOption(
            base.balance.typeId,
            "ANNUAL",
            "Annual leave",
            LocalDate(2026, 1, 1),
            true,
            true,
            false,
            true,
            0,
            0,
        )
    val value =
        LeaveRequestPreview(
            base.employee.id,
            base.clock.now(),
            "Asia/Jakarta",
            LeaveRequestPolicy(type.id, type.code, 0, type.name, true, true, false),
            listOf(
                LeaveDay(
                    selected.days.single().date,
                    LeavePortion.FIRST_HALF,
                    Instant.parse("2026-10-15T01:00:00Z"),
                    Instant.parse("2026-10-15T10:00:00Z"),
                    480,
                    240,
                )
            ),
            LeaveDays(1),
            emptyList(),
            listOf(
                LeavePreviewBalance(
                    2026,
                    LeaveDays(21),
                    LeaveDays(2),
                    LeaveDays(1),
                    LeaveDays(1),
                    LeaveDays(20),
                    3,
                )
            ),
            "a".repeat(64),
        )
    var typeReads = 0
    var previewReads = 0
    var typesCall: suspend (LeaveTypeSelection) -> AppResult<LeavePage<LeaveTypeOption>> = {
        AppResult.Success(LeavePage(listOf(type)))
    }
    var previewCall: suspend (LeaveDraftSelection) -> AppResult<LeaveRequestPreview> = {
        AppResult.Success(value)
    }
    val remote =
        object : LeavePreparationRepository {
            override suspend fun types(
                access: LeaveAccess,
                selection: LeaveTypeSelection,
            ): AppResult<LeavePage<LeaveTypeOption>> {
                typeReads++
                return typesCall(selection)
            }

            override suspend fun preview(
                access: LeaveAccess,
                selection: LeaveDraftSelection,
            ): AppResult<LeaveRequestPreview> {
                previewReads++
                return previewCall(selection)
            }
        }
    val types =
        LoadLeaveTypes(remote, base.sessions, base.workspaces, base.clock, base.build, base.replica)
    val preview =
        PreviewLeaveRequest(
            remote,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            base.replica,
        )
}
