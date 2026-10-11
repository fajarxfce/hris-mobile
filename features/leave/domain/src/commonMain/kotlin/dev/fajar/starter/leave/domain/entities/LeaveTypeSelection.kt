package dev.fajar.starter.leave.domain.entities

import kotlinx.datetime.LocalDate

data class LeaveTypeSelection(val asOf: LocalDate, val after: String? = null)
