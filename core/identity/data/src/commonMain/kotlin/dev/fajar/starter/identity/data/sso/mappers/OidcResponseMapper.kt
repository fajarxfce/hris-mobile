package dev.fajar.starter.identity.data.sso.mappers

import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.identity.data.sso.boundary.*
import dev.fajar.starter.identity.data.sso.dto.*
import dev.fajar.starter.identity.domain.sso.entities.SsoProof

fun OidcResponseDto.toSsoProof(
    providerId: String,
    platform: AppPlatform,
    request: OidcRequestDto,
): SsoProof {
    if (state != request.state) throw OidcProtocolException()
    if (error == "access_denied") throw BrowserAuthorizationCancelled()
    if (error != null || code.isNullOrBlank() || code.length > 4096) throw OidcProtocolException()
    return SsoProof(
        providerId,
        platform,
        code,
        request.codeVerifier,
        request.nonce,
        request.redirectUri,
    )
}
