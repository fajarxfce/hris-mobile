package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class RefreshRequest(val refreshToken: String) {
    override fun toString() = "RefreshRequest([redacted])"
}
