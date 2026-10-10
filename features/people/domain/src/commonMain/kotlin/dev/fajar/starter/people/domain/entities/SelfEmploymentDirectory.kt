package dev.fajar.starter.people.domain.entities

import kotlinx.datetime.LocalDate

data class SelfEmploymentDirectory(
    val asOf: LocalDate,
    val timezone: String,
    val items: List<Employment>,
    val nextCursor: String?,
)
