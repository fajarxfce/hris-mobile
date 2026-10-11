package dev.fajar.starter.leave.domain.entities

import kotlinx.datetime.LocalDate

data class LeaveTypeOption(
    val id: String,
    val code: String,
    val name: String,
    val effectiveFrom: LocalDate,
    val paid: Boolean,
    val allowPartialDays: Boolean,
    val attachmentRequired: Boolean,
    val active: Boolean,
    val version: Long,
    val appliedRevision: Long,
)
