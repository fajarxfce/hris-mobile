package dev.fajar.starter.people.domain.entities

import kotlinx.datetime.LocalDate

data class CompanyDirectory(
    val asOf: LocalDate,
    val timezone: String,
    val items: List<DirectoryEmployee>,
    val nextCursor: String?,
) {
    override fun toString() = "CompanyDirectory([redacted])"
}
