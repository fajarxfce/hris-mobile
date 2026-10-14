package dev.fajar.starter.people.domain.profile.entities

import dev.fajar.starter.people.domain.entities.Employment
import dev.fajar.starter.people.domain.entities.EmploymentProfile
import kotlin.time.Instant

data class SelfProfileView(
    val employments: List<Employment>,
    val selectedId: String?,
    val profile: EmploymentProfile?,
    val refreshedAt: Instant,
    val requestedId: String?,
) {
    override fun toString() = "SelfProfileView(employments=${employments.size})"
}
