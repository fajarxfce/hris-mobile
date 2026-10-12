package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimePlanningReview
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimePlanningSelection

interface OvertimePlanningRepository {
    suspend fun review(
        access: WorkforceAccess,
        selection: OvertimePlanningSelection,
    ): AppResult<OvertimePlanningReview>
}
