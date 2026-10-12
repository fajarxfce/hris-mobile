package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface OvertimeCommandPayloadDto {
    @Serializable
    @SerialName("PLAN")
    data class Plan(
        val expectedEmploymentVersion: Long,
        val requested: OvertimeIntervalDto,
        val planningRevision: String,
    ) : OvertimeCommandPayloadDto

    @Serializable
    @SerialName("ACTUAL")
    data class Actual(val expectedVersion: Long, val actual: OvertimeIntervalDto) :
        OvertimeCommandPayloadDto

    @Serializable
    @SerialName("WITHDRAWAL")
    data class Withdrawal(val expectedVersion: Long) : OvertimeCommandPayloadDto
}
