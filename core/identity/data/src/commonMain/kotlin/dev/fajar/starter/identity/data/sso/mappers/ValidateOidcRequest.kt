package dev.fajar.starter.identity.data.sso.mappers

import dev.fajar.starter.identity.data.sso.boundary.OidcProtocolException
import dev.fajar.starter.identity.data.sso.dto.OidcRequestDto
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Validate untrusted discovery metadata before opening the provider's authorization endpoint. */
fun validateOidcRequest(request: OidcRequestDto, expectedIssuer: String) {
    if (
        request.issuer != expectedIssuer ||
            request.state.isBlank() ||
            request.nonce.isBlank() ||
            request.codeVerifier.length !in 43..128
    )
        throw OidcProtocolException()
    listOf(request.authorizationEndpoint, request.tokenEndpoint).forEach { endpoint ->
        val url = Url(endpoint)
        if (
            url.protocol != URLProtocol.HTTPS ||
                url.host.isBlank() ||
                url.user != null ||
                url.password != null ||
                url.fragment.isNotEmpty()
        )
            throw OidcProtocolException()
    }
}
