package dev.fajar.starter.leave.presentation.formatters

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.AppBadgeTone
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.localization.*
import kotlinx.datetime.LocalDate

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

fun LeavePortion.label(): AppString =
    when (this) {
        LeavePortion.FULL -> AppString.LeaveFullDay
        LeavePortion.FIRST_HALF -> AppString.LeaveFirstHalf
        LeavePortion.SECOND_HALF -> AppString.LeaveSecondHalf
    }

fun LeaveChangeKind.label(): AppString =
    when (this) {
        LeaveChangeKind.SUBMITTED -> AppString.LeaveSubmitted
        LeaveChangeKind.DECIDED -> AppString.LeaveDecisionRecorded
        LeaveChangeKind.WITHDRAWN -> AppString.LeaveWithdrawn
        LeaveChangeKind.CANCELLATION_REQUESTED -> AppString.LeaveCancellationRequested
    }

@Composable
fun leaveDateRange(from: LocalDate, until: LocalDate): String =
    if (from == until) formatLocalDate(from.toString(), appLanguageTag())
    else
        formatLocalDate(from.toString(), appLanguageTag()) +
            " – " +
            formatLocalDate(until.toString(), appLanguageTag())

@Composable
fun leaveCarryValidityText(carry: LeaveCarryBalance): String =
    carry.validThrough?.let {
        appString(
            AppString.LeaveCarryValidThrough,
            formatLocalDate(it.toString(), appLanguageTag()),
            carry.timezone,
        )
    } ?: appString(AppString.LeaveCarryNoDeadline)

fun LeaveStatus.badgeTone(): AppBadgeTone =
    when (this) {
        LeaveStatus.PENDING -> AppBadgeTone.Caution
        LeaveStatus.APPROVED -> AppBadgeTone.Positive
        LeaveStatus.REJECTED -> AppBadgeTone.Negative
        LeaveStatus.CANCELLED -> AppBadgeTone.Neutral
        LeaveStatus.CANCELLATION_PENDING -> AppBadgeTone.Caution
    }
