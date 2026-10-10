package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SignInRequest(val email: String, val password: String) {
    override fun toString(): String = "SignInRequest([redacted])"
}
