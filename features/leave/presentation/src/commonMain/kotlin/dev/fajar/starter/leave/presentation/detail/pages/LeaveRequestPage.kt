package dev.fajar.starter.leave.presentation.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.presentation.detail.*
import dev.fajar.starter.leave.presentation.detail.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun LeaveRequestPage(
    state: LeaveRequestState,
    list: LazyListState,
    onEvent: (LeaveRequestEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 16.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.LeaveRequestDetails),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(LeaveRequestEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(LeaveRequestEvent.RefreshRequested) },
                        enabled = !state.refreshing && !state.action.working,
                    )
                },
            )
        },
    ) {
        if (state.loading || state.refreshing || state.action.working)
            item(key = "loading") { AppLoading() }
        state.failure?.let { failure ->
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(LeaveRequestEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(LeaveRequestEvent.RefreshRequested) },
                            enabled = !state.refreshing && !state.action.working,
                        )
                }
            }
        }
        val content = state.content
        if (content != null) {
            val request = content.request
            item(key = "summary") { LeaveRequestSummaryCard(request) }
            if (state.action.command != null || state.availableActions.isNotEmpty())
                item(key = "actions") { LeaveRequestActions(state, onEvent) }
            item(key = "reason") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(
                        appString(AppString.LeaveReason),
                        style = FluentTheme.typography.bodyStrong,
                    )
                    AppText(request.reason)
                }
            }
            item(key = "dates-heading") {
                AppText(appString(AppString.LeaveDates), style = FluentTheme.typography.subtitle)
            }
            items(request.days, key = { "day/${it.date}" }) { LeaveDayRow(it) }
            if (request.attachments.isNotEmpty()) {
                item(key = "attachments-heading") {
                    AppText(
                        appString(AppString.LeaveAttachments),
                        style = FluentTheme.typography.subtitle,
                    )
                }
                items(request.attachments, key = { "attachment/${it.revisionId}" }) { attachment ->
                    AppText(attachment.fileName)
                }
            }
            item(key = "history-heading") {
                AppText(appString(AppString.LeaveActivity), style = FluentTheme.typography.subtitle)
            }
            items(request.history.items, key = { "change/${it.version}" }) { LeaveChangeRow(it) }
            if (request.history.items.isEmpty())
                item(key = "history-empty") { AppText(appString(AppString.LeaveNoEarlierActivity)) }
        }
        if (
            !state.readRestricted &&
                (state.visibleSelection?.historyAfter != null ||
                    content?.request?.history?.nextCursor != null)
        ) {
            item(key = "history-pages") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.visibleSelection?.historyAfter != null)
                        AppTextButton(
                            appString(AppString.LeaveLatestActivity),
                            { onEvent(LeaveRequestEvent.HistoryPageRequested(null)) },
                            enabled = !state.refreshing && !state.action.working,
                        )
                    if (content?.request?.history?.nextCursor != null)
                        AppTextButton(
                            appString(AppString.LeaveEarlierActivity),
                            {
                                onEvent(
                                    LeaveRequestEvent.HistoryPageRequested(
                                        content.request.history.nextCursor
                                    )
                                )
                            },
                            enabled = !state.refreshing && !state.action.working,
                        )
                }
            }
        }
        if (content != null)
            item(key = "saved") {
                AppText(
                    appString(
                        AppString.LeaveSaved,
                        formatDate(content.fetchedAt.toEpochMilliseconds(), appLanguageTag()),
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        else if (!state.loading && !state.refreshing && state.failure == null)
            item(key = "empty") { AppText(appString(AppString.LeaveNoSaved)) }
    }
    state.action.form?.let { LeaveActionDialog(it, state.action.working, onEvent) }
}
