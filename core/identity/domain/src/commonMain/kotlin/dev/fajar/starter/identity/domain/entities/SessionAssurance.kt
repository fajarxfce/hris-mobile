package dev.fajar.starter.identity.domain.entities

data class SessionAssurance(
    val required: Boolean,
    val verified: Boolean,
    val setupAvailable: Boolean,
    val validUntilEpochMillis: Long?,
    val recentUntilEpochMillis: Long?,
)
