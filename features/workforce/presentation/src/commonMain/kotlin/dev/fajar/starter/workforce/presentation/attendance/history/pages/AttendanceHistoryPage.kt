package dev.fajar.starter.workforce.presentation.attendance.history.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.history.*
import dev.fajar.starter.workforce.presentation.attendance.history.widgets.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun AttendanceHistoryPage(
    state: AttendanceHistoryState,
    list: LazyListState,
    onEvent: (AttendanceHistoryEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 12.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.Attendance),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(AttendanceHistoryEvent.BackRequested) },
                        )
                    },
                    actions = {
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(AttendanceHistoryEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                        )
                    },
                )
                Box(Modifier.padding(horizontal = 16.dp)) {
                    AttendanceMonthBar(state.month, onEvent)
                }
            }
        },
    ) {
        if (state.loading || state.refreshing) item(key = "loading") { AppLoading() }
        val failure = state.failure
        if (failure != null)
            item(key = "failure") {
                AppFeedback(failureText(failure))
                if (state.needsVerification)
                    AppTextButton(
                        appString(AppString.VerifyAccount),
                        { onEvent(AttendanceHistoryEvent.VerificationRequested) },
                    )
            }
        val content = state.content
        if (content != null) {
            if (content.snapshot.directory.items.isEmpty())
                item(key = "employment-empty") {
                    AppText(appString(AppString.CalendarNoEmployment))
                }
            if (
                content.snapshot.directory.items.size > 1 ||
                    content.snapshot.directory.nextCursor != null ||
                    state.selection.after != null
            ) {
                item(key = "employment-label") {
                    AppHeading(
                        appString(AppString.ChooseEmployment),
                        style = FluentTheme.typography.bodyStrong,
                    )
                }
                items(content.snapshot.directory.items, key = { "employee/${it.id}" }) { employee ->
                    AppRadioRow(
                        employee.employeeNumber,
                        employee.id == content.employee?.id,
                        { onEvent(AttendanceHistoryEvent.EmploymentSelected(employee.id)) },
                    )
                }
            }
            if (content.employee != null) {
                item(key = "metrics") { AttendanceMetrics(content.summary) }
                item(key = "metrics-basis") {
                    AppText(
                        appString(AppString.AttendanceMetricsBasis),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
                item(key = "history-label") {
                    AppHeading(
                        appString(AppString.AttendanceHistory),
                        style = FluentTheme.typography.bodyStrong,
                    )
                }
                if (content.summary.days.isEmpty())
                    item(key = "empty") {
                        AppText(appString(AppString.AttendanceNoHistory), color = AppColors.muted)
                    }
                items(content.summary.days.asReversed(), key = { "day/${it.attendance.date}" }) {
                    day ->
                    AttendanceHistoryRow(day, content.snapshot.directory.timezone) {
                        onEvent(AttendanceHistoryEvent.DaySelected(day.attendance.date))
                    }
                }
            }
            if (content.snapshot.directory.nextCursor != null)
                item(key = "next-employments") {
                    AppTextButton(
                        appString(AppString.MoreEmployments),
                        {
                            onEvent(
                                AttendanceHistoryEvent.EmploymentPageRequested(
                                    content.snapshot.directory.nextCursor
                                )
                            )
                        },
                    )
                }
            if (state.selection.after != null)
                item(key = "first-employments") {
                    AppTextButton(
                        appString(AppString.CalendarFirstEmployments),
                        { onEvent(AttendanceHistoryEvent.EmploymentPageRequested(null)) },
                    )
                }
        } else if (!state.loading && !state.refreshing && failure == null) {
            item(key = "empty") {
                AppText(appString(AppString.AttendanceNoHistory), color = AppColors.muted)
            }
        }
    }
    if (state.picker != null && state.month != null)
        AttendanceMonthDialog(state.picker, state.month.selected, onEvent)
    if (state.selectedDay != null && state.content != null)
        AttendanceHistoryDayDialog(state.selectedDay, state.content.snapshot.directory.timezone) {
            onEvent(AttendanceHistoryEvent.DayDismissed)
        }
}
