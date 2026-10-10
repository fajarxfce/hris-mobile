package dev.fajar.starter.network

import io.ktor.http.URLProtocol
import io.ktor.http.Url

data class HttpClientSettings(
    val baseUrl: String,
    val requestTimeoutMillis: Long = 15_000,
    val connectTimeoutMillis: Long = 10_000,
    val socketTimeoutMillis: Long = 15_000,
    val allowCleartext: Boolean = false,
) {
    init {
        val url = Url(baseUrl)
        require(
            url.protocol == URLProtocol.HTTPS ||
                (allowCleartext && url.protocol == URLProtocol.HTTP)
        ) {
            "An HTTPS API endpoint is required."
        }
        require(
            url.user == null &&
                url.password == null &&
                url.parameters.isEmpty() &&
                url.fragment.isEmpty()
        ) {
            "Credentials and query parameters do not belong in API configuration."
        }
        require(requestTimeoutMillis > 0 && connectTimeoutMillis > 0 && socketTimeoutMillis > 0)
    }
}

object HttpClients {
    const val Oidc = "oidc"
    const val Public = "public-http"
    const val Authenticated = "authenticated-http"
}
