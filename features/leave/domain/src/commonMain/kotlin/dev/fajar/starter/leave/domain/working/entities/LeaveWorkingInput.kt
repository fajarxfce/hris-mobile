package dev.fajar.starter.leave.domain.working.entities

import dev.fajar.starter.leave.domain.entities.LeaveAttachment
import dev.fajar.starter.leave.domain.entities.LeavePortion
import dev.fajar.starter.leave.domain.entities.LeaveTypeOption
import kotlinx.datetime.LocalDate

/** Unfinished input; selected metadata does not authorize a submission. */
data class LeaveWorkingInput(
    val from: LocalDate,
    val until: LocalDate,
    val type: LeaveTypeOption? = null,
    val portion: LeavePortion = LeavePortion.FULL,
    val reason: String = "",
    val attachments: List<LeaveAttachment> = emptyList(),
)
