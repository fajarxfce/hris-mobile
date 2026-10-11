package dev.fajar.starter.leave.presentation.formatters

import dev.fajar.starter.leave.domain.entities.LeaveCommandOutcome
import dev.fajar.starter.leave.domain.entities.LeaveRequestAction
import dev.fajar.starter.localization.AppString

fun leaveActionLabel(action: LeaveRequestAction): AppString =
    when (action) {
        LeaveRequestAction.WITHDRAW -> AppString.LeaveWithdraw
        LeaveRequestAction.REQUEST_CANCELLATION -> AppString.LeaveCancelRequest
        LeaveRequestAction.WITHDRAW_CANCELLATION -> AppString.LeaveWithdrawCancellation
    }

fun leaveActionPrompt(action: LeaveRequestAction): AppString =
    when (action) {
        LeaveRequestAction.WITHDRAW -> AppString.LeaveWithdrawPrompt
        LeaveRequestAction.REQUEST_CANCELLATION -> AppString.LeaveCancelPrompt
        LeaveRequestAction.WITHDRAW_CANCELLATION -> AppString.LeaveWithdrawCancellationPrompt
    }

fun leaveActionStatus(outcome: LeaveCommandOutcome): AppString =
    when (outcome) {
        LeaveCommandOutcome.Pending -> AppString.LeaveActionQueued
        is LeaveCommandOutcome.Unconfirmed -> AppString.LeaveActionUnconfirmed
        is LeaveCommandOutcome.Rejected -> AppString.LeaveActionRejected
        is LeaveCommandOutcome.Confirmed -> AppString.LeaveActionConfirmed
    }
