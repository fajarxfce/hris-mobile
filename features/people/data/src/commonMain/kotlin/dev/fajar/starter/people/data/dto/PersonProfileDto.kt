package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PersonProfileDto(
    val personId: String,
    val ownerCompanyId: String,
    val accountId: String? = null,
    val legalName: String,
    val birthDate: String? = null,
    val nationality: String,
    val email: String? = null,
    val version: Long,
)
