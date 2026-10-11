package dev.fajar.starter.workforce.domain.entities

import kotlinx.datetime.LocalDate

data class WorkforceDirectory(
    val accountId: String,
    val companyId: String,
    val asOf: LocalDate,
    val timezone: String,
    val items: List<WorkforceEmployment>,
    val nextCursor: String?,
)
