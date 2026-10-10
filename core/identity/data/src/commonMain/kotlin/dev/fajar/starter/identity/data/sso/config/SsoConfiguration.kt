package dev.fajar.starter.identity.data.sso.config

import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.config.OidcClientSettings

/** Immutable build configuration selected by the platform composition root. */
data class SsoConfiguration(val clients: List<OidcClientSettings>, val platform: AppPlatform)
