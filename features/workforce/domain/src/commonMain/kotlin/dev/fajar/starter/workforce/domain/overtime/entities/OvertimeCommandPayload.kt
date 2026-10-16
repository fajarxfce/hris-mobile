package dev.fajar.starter.workforce.domain.overtime.entities

sealed interface OvertimeCommandPayload {
    data class Plan(
        val expectedEmploymentVersion: Long,
        val requested: OvertimeInterval,
        val planningRevision: String,
        val workingRevision: String? = null,
    ) : OvertimeCommandPayload

    data class Actual(val expectedVersion: Long, val actual: OvertimeInterval) :
        OvertimeCommandPayload

    data class Withdrawal(val expectedVersion: Long) : OvertimeCommandPayload
}
