package dev.fajar.starter.leave.domain.entities

import kotlinx.datetime.LocalDate

data class LeaveDirectory(
    val accountId: String,
    val companyId: String,
    val asOf: LocalDate,
    val timezone: String,
    val items: List<LeaveEmployment>,
    val nextCursor: String?,
)
