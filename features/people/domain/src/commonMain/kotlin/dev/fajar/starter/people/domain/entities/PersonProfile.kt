package dev.fajar.starter.people.domain.entities

import kotlinx.datetime.LocalDate

data class PersonProfile(
    val personId: String,
    val ownerCompanyId: String,
    val accountId: String?,
    val legalName: String,
    val birthDate: LocalDate?,
    val nationality: String,
    val email: String?,
    val version: Long,
) {
    override fun toString() = "PersonProfile(version=$version)"
}
