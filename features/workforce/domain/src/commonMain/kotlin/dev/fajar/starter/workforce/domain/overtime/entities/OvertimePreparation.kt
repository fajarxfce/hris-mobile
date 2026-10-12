package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Instant

/** A short-lived review; the server independently authorizes and validates the plan. */
data class OvertimePreparation(
    val access: WorkforceAccess,
    val selection: OvertimePlanningSelection,
    val review: OvertimePlanningReview,
    val fetchedAt: Instant,
    val expiresAt: Instant,
)
