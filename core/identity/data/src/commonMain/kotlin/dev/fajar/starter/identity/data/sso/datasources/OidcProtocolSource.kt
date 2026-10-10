package dev.fajar.starter.identity.data.sso.datasources

import dev.fajar.starter.common.config.OidcPlatformSettings
import dev.fajar.starter.identity.data.sso.dto.OidcRequestDto

interface OidcProtocolSource {
    suspend fun prepare(issuer: String, settings: OidcPlatformSettings): OidcRequestDto
}
