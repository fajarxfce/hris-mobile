package dev.fajar.starter.identity.data.sso.dto

data class OidcResponseDto(val code: String?, val state: String?, val error: String? = null) {
    override fun toString() = "OidcResponseDto([redacted])"
}
