package dev.fajar.starter.workforce.presentation.overtime.formatters

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.AppBadgeTone
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.datetime.*

fun OvertimeStatus?.label(): AppString =
    when (this) {
        null -> AppString.OvertimeAllStatuses
        OvertimeStatus.PLANNED -> AppString.OvertimePlanned
        OvertimeStatus.PENDING -> AppString.OvertimePending
        OvertimeStatus.APPROVED -> AppString.OvertimeApproved
        OvertimeStatus.REJECTED -> AppString.OvertimeRejected
        OvertimeStatus.WITHDRAWN -> AppString.OvertimeWithdrawn
    }

fun OvertimeChangeKind.label(): AppString =
    when (this) {
        OvertimeChangeKind.PLANNED -> AppString.OvertimePlannedActivity
        OvertimeChangeKind.ACTUAL_SUBMITTED -> AppString.OvertimeActualActivity
        OvertimeChangeKind.DECIDED -> AppString.OvertimeDecisionActivity
        OvertimeChangeKind.WITHDRAWN -> AppString.OvertimeWithdrawnActivity
    }

@Composable
fun overtimeTimeRange(interval: OvertimeInterval, timezone: String, workDate: LocalDate): String {
    val zone = TimeZone.of(timezone)
    val start = interval.startsAt.toLocalDateTime(zone)
    val end = interval.endsAt.toLocalDateTime(zone)
    return if (start.date == workDate && end.date == workDate)
        appString(AppString.OvertimeTimeRange, start.time.toString(), end.time.toString())
    else
        appString(
            AppString.OvertimeDatedTimeRange,
            formatLocalDate(start.date.toString(), appLanguageTag()),
            start.time.toString(),
            formatLocalDate(end.date.toString(), appLanguageTag()),
            end.time.toString(),
        )
}

fun OvertimeStatus.badgeTone(): AppBadgeTone =
    when (this) {
        OvertimeStatus.PLANNED -> AppBadgeTone.Info
        OvertimeStatus.PENDING -> AppBadgeTone.Caution
        OvertimeStatus.APPROVED -> AppBadgeTone.Positive
        OvertimeStatus.REJECTED -> AppBadgeTone.Negative
        OvertimeStatus.WITHDRAWN -> AppBadgeTone.Neutral
    }
