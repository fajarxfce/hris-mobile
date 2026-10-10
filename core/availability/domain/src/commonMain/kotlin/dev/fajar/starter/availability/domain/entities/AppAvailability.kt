package dev.fajar.starter.availability.domain.entities

sealed interface AppAvailability {
    data object Available : AppAvailability

    data class UpdateRecommended(val build: Long) : AppAvailability

    data class UpdateRequired(val build: Long) : AppAvailability

    data class Maintenance(val untilEpochMillis: Long) : AppAvailability
}
