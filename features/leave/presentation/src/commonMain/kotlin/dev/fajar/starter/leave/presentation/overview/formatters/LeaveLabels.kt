package dev.fajar.starter.leave.presentation.overview.formatters

import androidx.compose.runtime.Composable
import dev.fajar.starter.leave.domain.entities.LeaveDays
import dev.fajar.starter.leave.domain.entities.LeaveStatus
import dev.fajar.starter.localization.*

@Composable
fun leaveDaysText(days: LeaveDays): String =
    if (days.halfDays == 2) appString(AppString.LeaveOneDay)
    else
        appString(
            if (days.halfDays % 2 == 0) AppString.LeaveDays else AppString.LeaveHalfDays,
            formatNumber(days.halfDays.toLong() / 2, appLanguageTag()),
        )

fun LeaveStatus?.label(): AppString =
    when (this) {
        null -> AppString.LeaveAllStatuses
        LeaveStatus.PENDING -> AppString.LeavePending
        LeaveStatus.APPROVED -> AppString.LeaveApproved
        LeaveStatus.REJECTED -> AppString.LeaveRejected
        LeaveStatus.CANCELLED -> AppString.LeaveCancelled
        LeaveStatus.CANCELLATION_PENDING -> AppString.LeaveCancellationPending
    }
