package dev.fajar.starter.people.domain.profile.entities

import dev.fajar.starter.people.domain.entities.EmploymentProfile

/** One coherent employment/private-person snapshot and its independent sync version. */
data class SelfProfileRecord(
    val id: String,
    val version: Long,
    val timezone: String,
    val profile: EmploymentProfile,
) {
    override fun toString() = "SelfProfileRecord(version=$version)"
}
