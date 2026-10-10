package dev.fajar.starter.featureflags.data.mappers

import dev.fajar.starter.datastore.proto.UserPreferences
import dev.fajar.starter.featureflags.domain.entities.FlagSnapshot

fun UserPreferences.toFlagSnapshot() =
    FlagSnapshot(
        remoteValues = feature_flag_values.toMap(),
        overrides = feature_flag_overrides.toMap(),
        fetchedAtEpochMillis = feature_flags_fetched_at,
    )
