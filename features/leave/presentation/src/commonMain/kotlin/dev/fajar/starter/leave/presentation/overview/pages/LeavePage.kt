package dev.fajar.starter.leave.presentation.overview.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.presentation.overview.*
import dev.fajar.starter.leave.presentation.overview.formatters.label
import dev.fajar.starter.leave.presentation.overview.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Person

@Composable
fun LeavePage(state: LeaveState, list: LazyListState, onEvent: (LeaveEvent) -> Unit) {
    AppLazyPage(
        state = list,
        spacing = 12.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.Leave),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(LeaveEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(LeaveEvent.RefreshRequested) },
                        enabled = !state.refreshing,
                    )
                },
            )
        },
    ) {
        if (state.loading || state.refreshing) item(key = "loading") { AppLoading() }
        val failure = state.refreshFailure ?: state.cacheFailure
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(LeaveEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(LeaveEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                        )
                }
            }
        val content = state.content
        val selection = state.filters
        if (content?.directory?.items?.isEmpty() == true)
            item(key = "no-employment") { AppText(appString(AppString.CalendarNoEmployment)) }
        if (
            content != null &&
                (content.directory.items.size > 1 ||
                    content.directory.nextCursor != null ||
                    content.selection.employmentAfter != null)
        )
            item(key = "employment") {
                AppFilterChip(
                    appString(AppString.ChooseEmployment),
                    true,
                    { onEvent(LeaveEvent.EmploymentFilterRequested) },
                    icon = Icons.Regular.Person,
                )
            }
        if (selection?.employeeId != null && !state.readRestricted) {
            item(key = "balances-heading") {
                AppText(appString(AppString.LeaveBalances), style = FluentTheme.typography.subtitle)
            }
            selection.year?.let { year -> item(key = "year") { LeaveYearBar(year, true, onEvent) } }
            if (content?.balances?.items?.isEmpty() == true)
                item(key = "balances-empty") {
                    AppText(appString(AppString.LeaveNoBalances), color = AppColors.muted)
                }
            items(content?.balances?.items.orEmpty(), key = { "balance/${it.accountId}" }) {
                LeaveBalanceCard(it)
            }
            if (
                content != null &&
                    (selection.balanceAfter != null || content.balances.nextCursor != null)
            )
                item(key = "balance-pages") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (selection.balanceAfter != null)
                            AppTextButton(
                                appString(AppString.LeaveFirstBalances),
                                { onEvent(LeaveEvent.BalancesPageRequested(null)) },
                                enabled = !state.refreshing,
                            )
                        if (content.balances.nextCursor != null)
                            AppTextButton(
                                appString(AppString.LeaveMoreBalances),
                                {
                                    onEvent(
                                        LeaveEvent.BalancesPageRequested(
                                            content.balances.nextCursor
                                        )
                                    )
                                },
                                enabled = !state.refreshing,
                            )
                    }
                }
            item(key = "requests-heading") {
                Column(
                    Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText(
                        appString(AppString.LeaveRequests),
                        style = FluentTheme.typography.subtitle,
                    )
                    AppText(
                        appString(AppString.LeaveAllDates),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
            }
            item(key = "status-filter") {
                AppFilterChip(
                    appString(selection.status.label()),
                    selection.status != null,
                    { onEvent(LeaveEvent.StatusFilterRequested) },
                )
            }
            if (content?.requests?.items?.isEmpty() == true)
                item(key = "requests-empty") {
                    AppText(appString(AppString.LeaveNoRequests), color = AppColors.muted)
                }
            items(content?.requests?.items.orEmpty(), key = { "request/${it.id}" }) {
                LeaveRequestRow(it)
            }
            if (
                content != null &&
                    (selection.requestAfter != null || content.requests.nextCursor != null)
            )
                item(key = "request-pages") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (selection.requestAfter != null)
                            AppTextButton(
                                appString(AppString.LeaveLatestRequests),
                                { onEvent(LeaveEvent.RequestsPageRequested(null)) },
                                enabled = !state.refreshing,
                            )
                        if (content.requests.nextCursor != null)
                            AppTextButton(
                                appString(AppString.LeaveOlderRequests),
                                {
                                    onEvent(
                                        LeaveEvent.RequestsPageRequested(
                                            content.requests.nextCursor
                                        )
                                    )
                                },
                                enabled = !state.refreshing,
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
        else if (!state.loading && !state.refreshing && failure == null)
            item(key = "empty") { AppText(appString(AppString.LeaveNoSaved)) }
    }
    if (state.dialog != null) LeaveFilterDialog(state, onEvent)
}
