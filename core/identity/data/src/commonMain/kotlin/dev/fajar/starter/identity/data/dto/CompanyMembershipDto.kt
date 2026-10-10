package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CompanyMembershipDto(
    val id: String,
    val name: String,
    val code: String,
    val timezone: String,
)
