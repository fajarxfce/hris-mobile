package dev.fajar.starter.people.data.profile.dto

import dev.fajar.starter.people.data.dto.EmploymentDetailsDto
import dev.fajar.starter.people.data.dto.PersonProfileDto
import kotlinx.serialization.Serializable

@Serializable
data class SelfProfileRecordDto(
    val id: String,
    val version: Long,
    val timezone: String,
    val employment: EmploymentDetailsDto,
    val profile: PersonProfileDto,
) {
    override fun toString() = "SelfProfileRecordDto(version=$version)"
}
