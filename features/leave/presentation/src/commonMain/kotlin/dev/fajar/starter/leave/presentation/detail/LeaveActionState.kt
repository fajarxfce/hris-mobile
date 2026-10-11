package dev.fajar.starter.leave.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.leave.domain.entities.LeaveCommandRecord

data class LeaveActionState(
    val command: LeaveCommandRecord? = null,
    val ready: Boolean = false,
    val working: Boolean = false,
    val form: LeaveActionForm? = null,
    val failure: Failure? = null,
)
