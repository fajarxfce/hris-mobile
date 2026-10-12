package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workforce.domain.entities.WorkDay
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class OvertimeRequest(
    val id: String,
    val employeeId: String,
    val employeeNumber: String,
    val employeeName: String,
    val requesterAccountId: String?,
    val authorId: String,
    val createdAt: Instant,
    val workDate: LocalDate,
    val timezone: String,
    val schedule: WorkDay,
    val requested: OvertimeInterval,
    val reason: String,
    val status: OvertimeStatus,
    val actual: OvertimeInterval?,
    val submittedBy: String?,
    val submittedAt: Instant?,
    val approvalId: String?,
    val approvedMinutes: Int,
    val decidedAt: Instant?,
    val version: Long,
)
