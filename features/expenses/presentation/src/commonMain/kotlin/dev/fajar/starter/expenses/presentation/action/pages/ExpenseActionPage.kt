package dev.fajar.starter.expenses.presentation.action.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.expenses.domain.entities.ExpenseCommandPayload
import dev.fajar.starter.expenses.presentation.action.*
import dev.fajar.starter.expenses.presentation.action.formatters.*
import dev.fajar.starter.expenses.presentation.action.widgets.ExpenseActionConfirmation
import dev.fajar.starter.expenses.presentation.commands.widgets.ExpenseCommandCard
import dev.fajar.starter.expenses.presentation.detail.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun ExpenseActionPage(
    state: ExpenseActionState,
    list: LazyListState,
    onEvent: (ExpenseActionEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        maxWidth = 720.dp,
        spacing = 16.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(state.displayMode.title()),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ExpenseActionEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(ExpenseActionEvent.RefreshRequested) },
                        enabled = state.work == null,
                        loading = state.work == ExpenseActionWork.REFRESHING,
                    )
                },
            )
        },
        bottomBar = {
            if (state.restriction == null && state.command == null && state.content != null)
                AppActionBar {
                    if (state.available && !state.changed)
                        AppButton(
                            appString(state.mode.title()),
                            { onEvent(ExpenseActionEvent.Confirmed) },
                            Modifier.fillMaxWidth(),
                            enabled = state.canConfirm,
                            loading = state.work == ExpenseActionWork.SENDING,
                        )
                    else
                        AppTextButton(
                            appString(AppString.ExpenseViewClaim),
                            { onEvent(ExpenseActionEvent.OpenClaimRequested) },
                            Modifier.fillMaxWidth(),
                            enabled = state.canRecover,
                        )
                }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = state.content != null) }
        state.visibleFailure?.let { failure ->
            item(key = "failure") {
                AppFeedback(failureText(failure))
                if (state.needsVerification)
                    AppTextButton(
                        appString(AppString.VerifyAccount),
                        { onEvent(ExpenseActionEvent.VerificationRequested) },
                    )
                else if (state.restriction != null || state.content == null)
                    AppTextButton(
                        appString(AppString.TryAgain),
                        { onEvent(ExpenseActionEvent.RefreshRequested) },
                        enabled = state.work == null,
                    )
            }
        }
        if (state.restriction == null) {
            val record = state.command
            if (record != null) {
                state.content?.value?.claim?.let { claim ->
                    item(key = "summary") { ExpenseClaimSummaryCard(claim) }
                }
                item(key = "command") {
                    if (record.command.payload is ExpenseCommandPayload.SaveDraft) {
                        AppFeedback(appString(AppString.ExpensePendingRequest))
                        AppTextButton(
                            appString(AppString.ExpenseEditDraft),
                            { onEvent(ExpenseActionEvent.EditRetainedDraftRequested) },
                            enabled = state.canRecover,
                        )
                    } else
                        ExpenseCommandCard(
                            record,
                            state.work != null,
                            state.canRecover,
                            { onEvent(ExpenseActionEvent.RetryRequested) },
                            { onEvent(ExpenseActionEvent.OpenClaimRequested) },
                            { onEvent(ExpenseActionEvent.DiscardRequested) },
                        )
                }
            } else {
                val view = state.content?.value
                if (view != null) {
                    item(key = "summary") { ExpenseClaimSummaryCard(view.claim) }
                    item(key = "hint") { AppText(appString(state.mode.hint())) }
                    if (state.available && state.changed)
                        item(key = "changed") {
                            AppFeedback(appString(AppString.ExpenseActionChanged))
                            AppTextButton(
                                appString(AppString.ExpenseReviewLatest),
                                { onEvent(ExpenseActionEvent.ReviewLatestRequested) },
                                enabled = state.work == null,
                            )
                        }
                    else if (!state.available && state.mode != ExpenseActionMode.RECOVER)
                        item(key = "unavailable") {
                            AppFeedback(appString(AppString.ExpenseActionUnavailable))
                        }
                    items(state.lines, key = { "line/${it.id}" }) { ExpenseLineCard(it) }
                    if (state.available && state.form != null)
                        item(key = "reason") {
                            AppTextField(
                                appString(AppString.ExpenseActionReason),
                                state.form.reason,
                                { onEvent(ExpenseActionEvent.ReasonChanged(it)) },
                                enabled = state.editable,
                                maxLines = 4,
                            )
                        }
                }
            }
        }
    }
    if (state.dialog != null) ExpenseActionConfirmation(state, onEvent)
}
