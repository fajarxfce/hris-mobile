package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PersonSummaryDto(
    val id: String,
    val accountId: String? = null,
    val legalName: String,
    val email: String? = null,
)
