package dev.fajar.starter.workforce.domain.overtime.entities

import kotlin.time.Instant

data class OvertimeChange(
    val revision: Long,
    val kind: OvertimeChangeKind,
    val status: OvertimeStatus,
    val actual: OvertimeInterval?,
    val approvedMinutes: Int,
    val approvalId: String?,
    val actorId: String,
    val recordedAt: Instant,
    val reason: String,
)

enum class OvertimeChangeKind {
    PLANNED,
    ACTUAL_SUBMITTED,
    DECIDED,
    WITHDRAWN,
}
