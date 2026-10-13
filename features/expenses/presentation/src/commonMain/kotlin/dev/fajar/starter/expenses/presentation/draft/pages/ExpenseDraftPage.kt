package dev.fajar.starter.expenses.presentation.draft.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.commands.widgets.ExpenseCommandCard
import dev.fajar.starter.expenses.presentation.draft.*
import dev.fajar.starter.expenses.presentation.draft.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun ExpenseDraftPage(
    state: ExpenseDraftState,
    list: LazyListState,
    onEvent: (ExpenseDraftEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        maxWidth = 720.dp,
        spacing = 16.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(
                    if (state.editing) AppString.ExpenseEditDraft else AppString.ExpenseNewClaim
                ),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ExpenseDraftEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(ExpenseDraftEvent.ReloadRequested) },
                        enabled = state.work == null && !state.loading,
                        loading = state.loading,
                    )
                },
            )
        },
        bottomBar = {
            if (state.command == null && state.form != null && state.restriction == null)
                AppActionBar {
                    AppButton(
                        appString(AppString.ExpenseSaveDraft),
                        { onEvent(ExpenseDraftEvent.SaveRequested) },
                        Modifier.fillMaxWidth(),
                        enabled = state.canSave,
                        loading = state.work == ExpenseDraftWork.SAVING,
                    )
                }
        },
    ) {
        if (state.loading || state.work != null && state.command == null)
            item(key = "loading") { AppLoading(compact = true) }
        state.visibleFailure?.let { failure ->
            item(key = "failure") {
                AppFeedback(failureText(failure))
                AppTextButton(
                    appString(
                        if (state.needsVerification) AppString.VerifyAccount else AppString.TryAgain
                    ),
                    {
                        onEvent(
                            if (state.needsVerification) ExpenseDraftEvent.VerificationRequested
                            else ExpenseDraftEvent.ReloadRequested
                        )
                    },
                    enabled = state.work == null && !state.loading,
                )
            }
        }
        if (state.remoteChanged && state.visibleFailure == null)
            item(key = "changed") { AppFeedback(appString(AppString.ExpenseChanged)) }
        if (state.command != null && state.restriction == null)
            item(key = "command") {
                ExpenseCommandCard(
                    state.command,
                    state.work != null,
                    state.active && state.commandReady,
                    { onEvent(ExpenseDraftEvent.RetryRequested) },
                    { onEvent(ExpenseDraftEvent.OpenClaimRequested) },
                    { onEvent(ExpenseDraftEvent.DiscardCommandRequested) },
                )
            }
        val form = state.form
        if (form != null && state.command == null && state.restriction == null) {
            item(key = "title") {
                AppTextField(
                    appString(AppString.ExpenseTitle),
                    form.title,
                    { onEvent(ExpenseDraftEvent.TitleChanged(it)) },
                    enabled = state.editable,
                )
            }
            item(key = "description") {
                AppTextField(
                    appString(AppString.ExpenseDescriptionOptional),
                    form.description,
                    { onEvent(ExpenseDraftEvent.DescriptionChanged(it)) },
                    enabled = state.editable,
                    maxLines = 3,
                )
            }
            item(key = "items-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppHeading(
                        appString(AppString.ExpenseLines),
                        Modifier.weight(1f),
                        style = FluentTheme.typography.bodyStrong,
                    )
                    AppIconButton(
                        Icons.Regular.Add,
                        appString(AppString.ExpenseAddItem),
                        { onEvent(ExpenseDraftEvent.ItemAdded) },
                        enabled = state.editable && form.lines.size < 20,
                    )
                }
            }
            if (form.lines.isEmpty())
                item(key = "empty") {
                    AppText(appString(AppString.ExpenseAddItemHint), color = AppColors.muted)
                }
            items(form.lines, key = { it.id }) { line ->
                ExpenseDraftLineRow(
                    line,
                    state.references?.categories?.find { it.id == line.categoryId }?.name,
                    state.editable,
                    { onEvent(ExpenseDraftEvent.ItemEdited(line.id)) },
                    { onEvent(ExpenseDraftEvent.ItemRemoved(line.id)) },
                )
            }
            item(key = "reason") {
                AppTextField(
                    appString(AppString.ExpenseChangeReason),
                    form.reason,
                    { onEvent(ExpenseDraftEvent.ReasonChanged(it)) },
                    enabled = state.editable,
                    maxLines = 3,
                )
            }
        }
    }
    if (state.dialog != null) ExpenseDraftConfirmation(state, onEvent)
}
