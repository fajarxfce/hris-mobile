package dev.fajar.starter.identity.data.sso.datasources

import dev.fajar.starter.common.config.OidcPlatformSettings
import dev.fajar.starter.identity.data.sso.dto.OidcRequestDto
import dev.fajar.starter.network.HttpClients
import io.ktor.client.HttpClient
import org.koin.core.annotation.*
import org.publicvalue.multiplatform.oidc.DefaultOpenIdConnectClient
import org.publicvalue.multiplatform.oidc.OpenIdConnectClientConfig
import org.publicvalue.multiplatform.oidc.types.CodeChallengeMethod

/**
 * The injected HTTP client owns transport resources; the SDK is used only for discovery and PKCE.
 */
@Single
class SdkOidcProtocolSource(@Named(HttpClients.Oidc) private val http: HttpClient) :
    OidcProtocolSource {
    override suspend fun prepare(issuer: String, settings: OidcPlatformSettings): OidcRequestDto {
        val client =
            DefaultOpenIdConnectClient(
                httpClient = http,
                config =
                    OpenIdConnectClientConfig(
                        discoveryUri = issuer.trimEnd('/') + "/.well-known/openid-configuration",
                        clientId = settings.clientId,
                        redirectUri = settings.redirectUri,
                        scope = "openid profile email",
                        codeChallengeMethod = CodeChallengeMethod.S256,
                    ),
            )
        client.discover()
        val metadata = checkNotNull(client.discoverDocument)
        val request =
            client.createAuthorizationCodeRequest {
                parameters.append("prompt", "login")
                parameters.append("max_age", "0")
            }
        return OidcRequestDto(
            checkNotNull(metadata.issuer),
            checkNotNull(metadata.authorization_endpoint),
            checkNotNull(metadata.token_endpoint),
            request.url.toString(),
            settings.clientId,
            settings.redirectUri,
            request.state,
            checkNotNull(request.nonce),
            request.pkce.codeVerifier,
        )
    }
}
