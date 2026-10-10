package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountDto(
    val id: String,
    val email: String,
    val displayName: String,
    val mfaConfigured: Boolean,
)
