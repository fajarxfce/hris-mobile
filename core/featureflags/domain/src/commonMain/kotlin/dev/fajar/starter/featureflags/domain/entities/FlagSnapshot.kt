package dev.fajar.starter.featureflags.domain.entities

data class FlagSnapshot(
    val remoteValues: Map<String, String> = emptyMap(),
    val overrides: Map<String, Boolean> = emptyMap(),
    val fetchedAtEpochMillis: Long = 0,
)
