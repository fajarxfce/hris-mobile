package dev.fajar.starter.workspace.domain.entities

import kotlin.time.Instant

sealed interface CompanyAvailability {
    data object Unknown : CompanyAvailability

    data object Available : CompanyAvailability

    data object ClientMetadataRequired : CompanyAvailability

    data class UpdateRequired(val minimumBuild: Long) : CompanyAvailability

    data class Maintenance(val endsAt: Instant) : CompanyAvailability
}
