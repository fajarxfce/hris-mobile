package dev.fajar.starter.expenses.presentation.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.ExpenseStatus
import dev.fajar.starter.expenses.presentation.detail.*
import dev.fajar.starter.expenses.presentation.detail.widgets.*
import dev.fajar.starter.expenses.presentation.widgets.ExpenseReadFeedback
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Edit

@Composable
fun ExpenseClaimPage(
    state: ExpenseClaimState,
    list: LazyListState,
    onEvent: (ExpenseClaimEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        maxWidth = 720.dp,
        spacing = 12.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.ExpenseClaim),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(ExpenseClaimEvent.BackRequested) },
                        )
                    },
                    actions = {
                        if (state.canEdit)
                            AppIconButton(
                                Icons.Regular.Edit,
                                appString(AppString.ExpenseEditDraft),
                                { onEvent(ExpenseClaimEvent.EditRequested) },
                                enabled = !state.refreshing,
                            )

                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(ExpenseClaimEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                            loading = state.refreshing,
                        )
                    },
                )
                state.failure?.let {
                    ExpenseReadFeedback(it, state.needsVerification, state.refreshing) {
                        onEvent(ExpenseClaimEvent.VerificationRequested)
                    }
                }
            }
        },
        bottomBar = {
            if (
                !state.readRestricted && (state.command != null || state.actionOptions.isNotEmpty())
            )
                AppActionBar { ExpenseClaimActions(state, onEvent) }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = state.content != null) }
        val snapshot = state.content?.takeUnless { state.readRestricted }
        val view = snapshot?.value
        if (view != null) {
            item(key = "summary") { ExpenseClaimSummaryCard(view.claim) }
            if (view.claim.draft.description.isNotBlank())
                item(key = "description") {
                    AppCard(padding = 16.dp, spacing = 8.dp) {
                        AppHeading(
                            appString(AppString.ExpenseDescription),
                            style = FluentTheme.typography.bodyStrong,
                        )
                        AppText(view.claim.draft.description)
                    }
                }
            item(key = "items-heading") {
                AppHeading(
                    appString(AppString.ExpenseLines),
                    style = FluentTheme.typography.bodyStrong,
                )
            }
            if (view.claim.draft.lines.isEmpty())
                item(key = "no-lines") {
                    AppText(appString(AppString.ExpenseNoLines), color = AppColors.muted)
                }
            items(state.lines, key = { "line/${it.id}" }) { ExpenseLineCard(it) }
            view.submission?.let { submission ->
                item(key = "approval") { ExpenseApprovalCard(submission) }
            }
            if (view.claim.status == ExpenseStatus.APPROVED)
                item(key = "payments") { ExpensePaymentCard(view.payments) }
            item(key = "activity-heading") {
                AppHeading(
                    appString(AppString.ExpenseActivity),
                    style = FluentTheme.typography.bodyStrong,
                )
            }
            if (view.history.items.isEmpty())
                item(key = "no-activity") {
                    AppText(appString(AppString.ExpenseNoEarlierActivity), color = AppColors.muted)
                }
            items(view.history.items, key = { "history/${it.version}" }) { ExpenseChangeRow(it) }
            if (view.historyAfter != null || view.history.nextCursor != null)
                item(key = "history-pages") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (view.historyAfter != null)
                            AppTextButton(
                                appString(AppString.ExpenseLatestActivity),
                                { onEvent(ExpenseClaimEvent.HistoryRequested(null)) },
                                enabled = !state.refreshing,
                            )
                        if (view.history.nextCursor != null)
                            AppTextButton(
                                appString(AppString.ExpenseEarlierActivity),
                                {
                                    onEvent(
                                        ExpenseClaimEvent.HistoryRequested(state.nextHistoryAfter)
                                    )
                                },
                                enabled = !state.refreshing,
                            )
                    }
                }
            item(key = "saved") {
                AppText(
                    appString(
                        AppString.UpdatedAt,
                        formatDate(snapshot.fetchedAt.toEpochMilliseconds(), appLanguageTag()),
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        } else if (!state.loading && !state.refreshing && state.failure == null)
            item(key = "no-cache") {
                AppText(appString(AppString.ExpenseClaimNoSaved), color = AppColors.muted)
            }
    }
}
