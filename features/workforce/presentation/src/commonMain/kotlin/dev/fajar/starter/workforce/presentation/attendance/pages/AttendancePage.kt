package dev.fajar.starter.workforce.presentation.attendance.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceKind
import dev.fajar.starter.workforce.presentation.attendance.*
import dev.fajar.starter.workforce.presentation.attendance.widgets.*
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarTimeRange
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun AttendancePage(
    state: AttendanceState,
    list: LazyListState,
    onEvent: (AttendanceEvent) -> Unit,
) {
    AppLazyPage(state = list, spacing = 12.dp, contentPadding = PaddingValues(20.dp)) {
        item(key = "header") {
            AppScreenHeader(
                appString(AppString.Attendance),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(AttendanceEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(AttendanceEvent.RefreshRequested) },
                        enabled = !state.refreshing && !state.working,
                    )
                },
            )
        }
        if (state.loading || state.refreshing || state.working)
            item(key = "loading") { AppLoading() }
        if (state.failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(state.failure))
                    if (state.needsVerification)
                        AppButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(AttendanceEvent.VerificationRequested) },
                        )
                    if (state.settingsTarget != null)
                        AppButton(
                            appString(AppString.OpenSettings),
                            { onEvent(AttendanceEvent.SettingsRequested) },
                            enabled = !state.working,
                            primary = false,
                        )
                    if (state.withoutLocationKind != null) {
                        AppText(
                            appString(AppString.AttendanceWithoutLocationHint),
                            color = AppColors.muted,
                        )
                        AppButton(
                            appString(AppString.AttendanceWithoutLocation),
                            {
                                onEvent(
                                    AttendanceEvent.CaptureRequested(
                                        state.withoutLocationKind,
                                        true,
                                    )
                                )
                            },
                            enabled = !state.working,
                            primary = false,
                        )
                    }
                }
            }
        val content = state.content
        if (content != null) {
            if (content.snapshot.directory.items.isEmpty())
                item(key = "empty") { AppText(appString(AppString.CalendarNoEmployment)) }
            if (
                content.snapshot.directory.items.size > 1 ||
                    content.snapshot.directory.nextCursor != null ||
                    state.selection.after != null
            ) {
                item(key = "choose") { AppText(appString(AppString.ChooseEmployment)) }
                items(content.snapshot.directory.items, key = { "employee/${it.id}" }) { employee ->
                    AppButton(
                        employee.employeeNumber,
                        { onEvent(AttendanceEvent.EmploymentSelected(employee.id)) },
                        primary = employee.id == content.employee?.id,
                        enabled = !state.working,
                    )
                }
            }
            if (content.employee != null)
                item(key = "capture") {
                    AppCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
                        AppText(content.employee.employeeNumber, color = AppColors.muted)
                        AppHeading(
                            formatLocalDate(content.date.toString(), appLanguageTag()),
                            style = FluentTheme.typography.subtitle,
                        )
                        if (content.shift != null) AppText(calendarTimeRange(content.shift))
                        AppText(content.snapshot.directory.timezone, color = AppColors.muted)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppButton(
                                appString(AppString.ClockIn),
                                {
                                    onEvent(
                                        AttendanceEvent.CaptureRequested(AttendanceKind.CLOCK_IN)
                                    )
                                },
                                Modifier.weight(1f),
                                enabled = content.canClockIn && !state.working && !state.refreshing,
                            )
                            AppButton(
                                appString(AppString.ClockOut),
                                {
                                    onEvent(
                                        AttendanceEvent.CaptureRequested(AttendanceKind.CLOCK_OUT)
                                    )
                                },
                                Modifier.weight(1f),
                                enabled =
                                    content.canClockOut && !state.working && !state.refreshing,
                                primary = false,
                            )
                        }
                        AppText(appString(AppString.AttendanceCaptureHint), color = AppColors.muted)
                    }
                }
            if (content.commands.isNotEmpty()) {
                item(key = "queue-title") {
                    AppHeading(
                        appString(AppString.AttendanceQueue),
                        style = FluentTheme.typography.bodyStrong,
                    )
                }
                items(content.commands, key = { "command/${it.command.id}" }) { record ->
                    AttendanceQueueCard(
                        record,
                        content.snapshot.directory.timezone,
                        state.working,
                        onEvent,
                    )
                }
                if (content.commands.size == 50)
                    item(key = "queue-limit") {
                        AppText(appString(AppString.AttendanceQueueLimit), color = AppColors.muted)
                    }
            }
            if (content.today != null)
                item(key = "today") {
                    AttendanceDayCard(content.today, content.snapshot.directory.timezone)
                }
            item(key = "history-title") {
                AppHeading(
                    appString(AppString.AttendanceHistory),
                    style = FluentTheme.typography.bodyStrong,
                )
            }
            items(
                content.snapshot.days.filter { it.date < content.date }.reversed(),
                key = { "day/${it.date}" },
            ) { day ->
                AttendanceDayCard(day, content.snapshot.directory.timezone)
            }
            if (content.snapshot.directory.nextCursor != null)
                item(key = "next") {
                    AppButton(
                        appString(AppString.MoreEmployments),
                        {
                            onEvent(
                                AttendanceEvent.EmploymentPageRequested(
                                    content.snapshot.directory.nextCursor
                                )
                            )
                        },
                        primary = false,
                        enabled = !state.working,
                    )
                }
            if (state.selection.after != null)
                item(key = "first") {
                    AppButton(
                        appString(AppString.CalendarFirstEmployments),
                        { onEvent(AttendanceEvent.EmploymentPageRequested(null)) },
                        primary = false,
                        enabled = !state.working,
                    )
                }
        }
    }
}
