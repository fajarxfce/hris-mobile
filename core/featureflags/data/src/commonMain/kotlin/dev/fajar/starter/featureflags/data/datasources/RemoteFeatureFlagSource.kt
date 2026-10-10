package dev.fajar.starter.featureflags.data.datasources

/** Activated remote parameters only. Defaults and local overrides belong to domain policy. */
interface RemoteFeatureFlagSource {
    suspend fun fetch(): Map<String, String>
}
