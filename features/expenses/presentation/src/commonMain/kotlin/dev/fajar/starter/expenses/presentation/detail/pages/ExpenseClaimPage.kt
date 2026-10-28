package dev.fajar.starter.expenses.presentation.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // Stopping clears financial content until local access is observed again. Do not
    // measure its scroll state against the temporary loading or denial rows.
    val feedbackList = rememberLazyListState()
    AppLazyPage(
        state = if (state.content != null && !state.readRestricted) list else feedbackList,
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
                            enabled = !state.busy,
                            loading = state.refreshing,
                        )
                    },
                )
                state.failure?.let {
                    ExpenseReadFeedback(it, state.needsVerification, state.busy) {
                        onEvent(ExpenseClaimEvent.VerificationRequested)
                    }
                }
                if (state.savingReceiptId != null)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppProgressRing(appString(AppString.DocumentSaving))
                        AppText(appString(AppString.DocumentSaving), Modifier.weight(1f))
                        AppTextButton(
                            appString(AppString.Cancel),
                            { onEvent(ExpenseClaimEvent.ExportCancelled) },
                        )
                    }
                else if (state.savedReceiptId != null && !state.readRestricted)
                    AppText(
                        appString(AppString.ExpenseReceiptSaved),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = FluentTheme.typography.caption,
                    )
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
            items(state.lines, key = { "line/${it.id}" }) {
                ExpenseLineCard(
                    it,
                    onSaveReceipt = { id -> onEvent(ExpenseClaimEvent.ExportRequested(id)) },
                    canSaveReceipt = state.canSaveReceipt,
                    savingReceiptId = state.savingReceiptId,
                )
            }
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
                                enabled = !state.busy,
                            )
                        if (view.history.nextCursor != null)
                            AppTextButton(
                                appString(AppString.ExpenseEarlierActivity),
                                {
                                    onEvent(
                                        ExpenseClaimEvent.HistoryRequested(state.nextHistoryAfter)
                                    )
                                },
                                enabled = !state.busy,
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
