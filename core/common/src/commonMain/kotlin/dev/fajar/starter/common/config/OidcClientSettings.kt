package dev.fajar.starter.common.config

/** Public OIDC client registration. Client secrets must never be distributed with an app. */
data class OidcClientSettings(
    val id: String,
    val label: String,
    val issuer: String,
    val platforms: Map<AppPlatform, OidcPlatformSettings>,
)
