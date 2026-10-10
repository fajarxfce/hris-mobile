package dev.fajar.starter.featureflags.data.datasources

import dev.fajar.starter.featureflags.data.errors.RemoteConfigUnavailableException

/** Desktop, tests, and hosts without a configured remote provider still use local flags. */
class UnavailableFeatureFlagSource : RemoteFeatureFlagSource {
    override suspend fun fetch(): Map<String, String> = throw RemoteConfigUnavailableException()
}
