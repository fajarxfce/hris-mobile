package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class LeaveRequestSummary(
    val id: String,
    val employeeId: String,
    val typeCode: String,
    val typeName: String,
    val from: LocalDate,
    val until: LocalDate,
    val charged: LeaveDays,
    val status: LeaveStatus,
    val submittedAt: Instant,
    val version: Long,
)
