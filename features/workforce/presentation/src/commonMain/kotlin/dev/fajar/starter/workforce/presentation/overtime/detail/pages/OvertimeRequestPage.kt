package dev.fajar.starter.workforce.presentation.overtime.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.calendar.widgets.CalendarDayCard
import dev.fajar.starter.workforce.presentation.overtime.detail.*
import dev.fajar.starter.workforce.presentation.overtime.detail.widgets.*
import dev.fajar.starter.workforce.presentation.overtime.widgets.OvertimeReadFeedback
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun OvertimeRequestPage(
    state: OvertimeRequestState,
    list: LazyListState,
    onEvent: (OvertimeRequestEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 16.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.OvertimeRequestDetails),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(OvertimeRequestEvent.BackRequested) },
                        )
                    },
                    actions = {
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(OvertimeRequestEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                            loading = state.refreshing,
                        )
                    },
                )
                state.failure?.let { failure ->
                    OvertimeReadFeedback(
                        failure,
                        state.needsVerification,
                        state.refreshing,
                        { onEvent(OvertimeRequestEvent.VerificationRequested) },
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = state.content != null) }
        val snapshot = state.content?.takeUnless { state.readRestricted }
        val details = snapshot?.value?.details
        if (details != null) {
            val request = details.request
            item(key = "summary") { OvertimeRequestSummary(details) }
            item(key = "planned") {
                OvertimeIntervalCard(
                    appString(AppString.OvertimePlannedTime),
                    request.requested,
                    request.timezone,
                    request.workDate,
                )
            }
            request.actual?.let { actual ->
                item(key = "actual") {
                    OvertimeIntervalCard(
                        appString(AppString.OvertimeActualTime),
                        actual,
                        request.timezone,
                        request.workDate,
                    )
                }
            }
            item(key = "reason") {
                AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
                    AppHeading(
                        appString(AppString.OvertimeReason),
                        style = FluentTheme.typography.bodyStrong,
                    )
                    AppText(request.reason)
                }
            }
            item(key = "schedule-heading") {
                AppHeading(
                    appString(AppString.OvertimeReviewedSchedule),
                    style = FluentTheme.typography.bodyStrong,
                )
            }
            item(key = "schedule") { CalendarDayCard(request.schedule, today = false) }
            item(key = "history-heading") {
                AppHeading(
                    appString(AppString.OvertimeActivity),
                    style = FluentTheme.typography.bodyStrong,
                )
            }
            items(details.history.items, key = { "change/${it.revision}" }) {
                OvertimeChangeRow(it)
            }
            if (details.history.items.isEmpty())
                item(key = "history-empty") {
                    AppText(appString(AppString.OvertimeNoEarlierActivity))
                }
            if (state.selection.historyAfter != null || details.history.nextCursor != null)
                item(key = "history-pages") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.selection.historyAfter != null)
                            AppTextButton(
                                appString(AppString.OvertimeLatestActivity),
                                { onEvent(OvertimeRequestEvent.HistoryPageRequested(null)) },
                                enabled = !state.refreshing,
                            )
                        details.history.nextCursor?.toLongOrNull()?.let { after ->
                            AppTextButton(
                                appString(AppString.OvertimeEarlierActivity),
                                { onEvent(OvertimeRequestEvent.HistoryPageRequested(after)) },
                                enabled = !state.refreshing,
                            )
                        }
                    }
                }
            item(key = "saved") {
                AppText(
                    appString(
                        AppString.OvertimeSaved,
                        formatDate(snapshot.fetchedAt.toEpochMilliseconds(), appLanguageTag()),
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        } else if (!state.loading && !state.refreshing && state.failure == null)
            item(key = "empty") { AppText(appString(AppString.OvertimeNoSaved)) }
        if (details == null && !state.readRestricted && state.selection.historyAfter != null)
            item(key = "latest-history") {
                AppTextButton(
                    appString(AppString.OvertimeLatestActivity),
                    { onEvent(OvertimeRequestEvent.HistoryPageRequested(null)) },
                    enabled = !state.refreshing,
                )
            }
    }
}
