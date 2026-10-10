package dev.fajar.starter.availability.domain.entities

data class AppPolicy(
    val minimumBuild: Long = 0,
    val recommendedBuild: Long = 0,
    val maintenanceUntilEpochMillis: Long? = null,
    val fetchedAtEpochMillis: Long = 0,
    val dismissedBuild: Long = 0,
)
