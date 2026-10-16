package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface OvertimeCommandPayloadDto {
    val workingRevision: String?

    @Serializable
    @SerialName("PLAN")
    data class Plan(
        val expectedEmploymentVersion: Long,
        val requested: OvertimeIntervalDto,
        val planningRevision: String,
        override val workingRevision: String? = null,
    ) : OvertimeCommandPayloadDto

    @Serializable
    @SerialName("ACTUAL")
    data class Actual(
        val expectedVersion: Long,
        val actual: OvertimeIntervalDto,
        override val workingRevision: String? = null,
    ) : OvertimeCommandPayloadDto

    @Serializable
    @SerialName("WITHDRAWAL")
    data class Withdrawal(val expectedVersion: Long, override val workingRevision: String? = null) :
        OvertimeCommandPayloadDto
}
