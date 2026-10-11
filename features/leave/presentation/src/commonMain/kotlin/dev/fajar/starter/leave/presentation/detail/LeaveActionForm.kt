package dev.fajar.starter.leave.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.leave.domain.entities.LeaveRequestAction

data class LeaveActionForm(
    val action: LeaveRequestAction,
    val version: Long,
    val reason: String = "",
    val failure: Failure? = null,
) {
    override fun toString() = "LeaveActionForm(action=$action, version=$version)"
}
