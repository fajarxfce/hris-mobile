package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimePlanningRepository
import dev.fajar.starter.workforce.domain.overtime.usecases.ReviewOvertimeSchedule
import kotlin.time.Instant

class OvertimePlanningFixture(now: () -> Instant = { Instant.parse("2026-10-12T01:00:00Z") }) {
    val overtime = OvertimeFixture(now)
    val base = overtime.base
    val selection = OvertimePlanningSelection(base.employee.id, base.directory.asOf)
    var review =
        OvertimePlanningReview(
            base.employee,
            selection.workDate,
            base.directory.asOf,
            "Asia/Jakarta",
            overtime.request.schedule,
            "a".repeat(64),
        )
    var calls = 0
    var reviewCall: suspend () -> AppResult<OvertimePlanningReview> = { AppResult.Success(review) }
    val remote =
        object : OvertimePlanningRepository {
            override suspend fun review(
                access: WorkforceAccess,
                selection: OvertimePlanningSelection,
            ): AppResult<OvertimePlanningReview> {
                calls++
                return reviewCall()
            }
        }
    val useCase =
        ReviewOvertimeSchedule(
            base.remote,
            remote,
            overtime.cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )

    suspend fun load(selected: OvertimePlanningSelection = selection) =
        useCase(overtime.workspace.id, selected)
}
