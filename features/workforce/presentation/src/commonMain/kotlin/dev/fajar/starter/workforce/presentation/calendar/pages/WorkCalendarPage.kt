package dev.fajar.starter.workforce.presentation.calendar.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.calendar.*
import dev.fajar.starter.workforce.presentation.calendar.widgets.CalendarDayCard
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowRight
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun WorkCalendarPage(
    state: WorkCalendarState,
    list: LazyListState,
    onEvent: (WorkCalendarEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 12.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                title = appString(AppString.WorkCalendar),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(WorkCalendarEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(WorkCalendarEvent.RefreshRequested) },
                        enabled = !state.refreshing,
                    )
                },
            )
        },
    ) {
        if (state.loading || state.refreshing) item(key = "loading") { AppLoading() }
        val failure = state.failure
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(WorkCalendarEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(WorkCalendarEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                        )
                }
            }
        val content = state.content
        if (content != null) {
            val snapshot = content.snapshot
            item(key = "week") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(
                        formatLocalDate(snapshot.period.from.toString(), appLanguageTag()) +
                            " – " +
                            formatLocalDate(snapshot.period.until.toString(), appLanguageTag())
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.CalendarPreviousWeek),
                            {
                                content.previousWeek?.let {
                                    onEvent(WorkCalendarEvent.WeekRequested(it))
                                }
                            },
                            enabled = content.previousWeek != null,
                        )
                        AppTextButton(
                            appString(AppString.CalendarCurrentWeek),
                            { onEvent(WorkCalendarEvent.CurrentWeekRequested) },
                        )
                        AppIconButton(
                            Icons.Regular.ArrowRight,
                            appString(AppString.CalendarNextWeek),
                            {
                                content.nextWeek?.let {
                                    onEvent(WorkCalendarEvent.WeekRequested(it))
                                }
                            },
                            enabled = content.nextWeek != null,
                        )
                    }
                }
            }
            if (snapshot.directory.items.isEmpty())
                item(key = "no-employment") { AppText(appString(AppString.CalendarNoEmployment)) }
            if (
                snapshot.directory.items.size > 1 ||
                    snapshot.directory.nextCursor != null ||
                    snapshot.selection.after != null
            ) {
                item(key = "choose") { AppText(appString(AppString.ChooseEmployment)) }
                items(snapshot.directory.items, key = { "employment/${it.id}" }) { employee ->
                    AppButton(
                        employee.employeeNumber,
                        { onEvent(WorkCalendarEvent.EmploymentSelected(employee.id)) },
                        Modifier.fillMaxWidth(),
                        primary = employee.id == snapshot.selection.employeeId,
                    )
                }
            } else if (snapshot.selection.employeeId != null)
                item(key = "employee-number") {
                    AppText(
                        appString(AppString.EmployeeNumber) +
                            ": " +
                            snapshot.directory.items.single().employeeNumber,
                        color = AppColors.muted,
                    )
                }
            if (snapshot.directory.nextCursor != null)
                item(key = "more") {
                    AppButton(
                        appString(AppString.MoreEmployments),
                        {
                            onEvent(
                                WorkCalendarEvent.EmploymentPageRequested(
                                    snapshot.directory.nextCursor
                                )
                            )
                        },
                        primary = false,
                    )
                }
            if (snapshot.selection.after != null)
                item(key = "first-page") {
                    AppButton(
                        appString(AppString.CalendarFirstEmployments),
                        { onEvent(WorkCalendarEvent.EmploymentPageRequested(null)) },
                        primary = false,
                    )
                }
            items(snapshot.calendar?.days.orEmpty(), key = { "day/${it.date}" }) { day ->
                CalendarDayCard(day, day.date == snapshot.directory.asOf)
            }
            item(key = "saved") {
                AppText(
                    appString(
                        AppString.CalendarSaved,
                        formatDate(snapshot.fetchedAt.toEpochMilliseconds(), appLanguageTag()),
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        } else if (!state.loading && !state.refreshing && failure == null)
            item(key = "empty") { AppText(appString(AppString.CalendarNoSaved)) }
    }
}
