package dev.fajar.starter.workforce.domain.overtime.entities

sealed interface OvertimeCommandPayload {
    val workingRevision: String?

    data class Plan(
        val expectedEmploymentVersion: Long,
        val requested: OvertimeInterval,
        val planningRevision: String,
        override val workingRevision: String? = null,
    ) : OvertimeCommandPayload

    data class Actual(
        val expectedVersion: Long,
        val actual: OvertimeInterval,
        override val workingRevision: String? = null,
    ) : OvertimeCommandPayload

    data class Withdrawal(val expectedVersion: Long, override val workingRevision: String? = null) :
        OvertimeCommandPayload
}
