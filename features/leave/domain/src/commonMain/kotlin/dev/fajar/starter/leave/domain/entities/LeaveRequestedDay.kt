package dev.fajar.starter.leave.domain.entities

import kotlinx.datetime.LocalDate

data class LeaveRequestedDay(val date: LocalDate, val portion: LeavePortion)
