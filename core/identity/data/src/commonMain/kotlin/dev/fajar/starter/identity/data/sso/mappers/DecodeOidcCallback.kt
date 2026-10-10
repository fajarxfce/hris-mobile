package dev.fajar.starter.identity.data.sso.mappers

import dev.fajar.starter.identity.data.sso.boundary.OidcProtocolException
import dev.fajar.starter.identity.data.sso.dto.OidcResponseDto
import io.ktor.http.Url

/** Raw callback decoding shared by browser-based platform adapters. */
fun decodeOidcCallback(callback: String, expectedRedirectUri: String): OidcResponseDto {
    val url = Url(callback)
    val expected = Url(expectedRedirectUri)
    if (
        url.protocol != expected.protocol ||
            url.host != expected.host ||
            url.port != expected.port ||
            url.encodedPath != expected.encodedPath ||
            url.fragment.isNotEmpty() ||
            url.user != null ||
            url.password != null
    )
        throw OidcProtocolException()
    val fields = listOf("code", "state", "error")
    if (fields.any { (url.parameters.getAll(it)?.size ?: 0) > 1 }) throw OidcProtocolException()
    return OidcResponseDto(url.parameters["code"], url.parameters["state"], url.parameters["error"])
}
