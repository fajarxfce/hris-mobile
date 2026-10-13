package dev.fajar.starter.expenses.presentation.overview.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.overview.*
import dev.fajar.starter.expenses.presentation.overview.widgets.*
import dev.fajar.starter.expenses.presentation.widgets.ExpenseReadFeedback
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun ExpensesPage(state: ExpensesState, list: LazyListState, onEvent: (ExpensesEvent) -> Unit) {
    AppLazyPage(
        state = list,
        maxWidth = 720.dp,
        spacing = 12.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.Expenses),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(ExpensesEvent.BackRequested) },
                        )
                    },
                    actions = {
                        if (state.canCreate)
                            AppIconButton(
                                Icons.Regular.Add,
                                appString(AppString.ExpenseNewClaim),
                                { onEvent(ExpensesEvent.CreateRequested) },
                                enabled = !state.refreshing,
                            )

                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(ExpensesEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                            loading = state.refreshing,
                        )
                    },
                )
                ExpenseFilterBar(state, onEvent)
                state.failure?.let {
                    ExpenseReadFeedback(it, state.needsVerification, state.refreshing) {
                        onEvent(ExpensesEvent.VerificationRequested)
                    }
                }
            }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = state.content != null) }
        val snapshot = state.content?.takeUnless { state.readRestricted }
        val content = snapshot?.value
        if (content != null && content.page.items.isEmpty())
            item(key = "empty") {
                AppText(
                    appString(
                        if (content.directory.items.isEmpty()) AppString.NoEmployment
                        else AppString.ExpensesEmpty
                    ),
                    color = AppColors.muted,
                )
            }
        items(content?.page?.items.orEmpty(), key = { it.id }) { claim ->
            ExpenseRow(claim) { onEvent(ExpensesEvent.ClaimSelected(claim.id)) }
        }
        if (content != null && (content.selection.after != null || content.page.nextCursor != null))
            item(key = "pages") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (content.selection.after != null)
                        AppTextButton(
                            appString(AppString.ExpensesFirstPage),
                            { onEvent(ExpensesEvent.PageRequested(null)) },
                            enabled = !state.refreshing,
                        )
                    if (content.page.nextCursor != null)
                        AppTextButton(
                            appString(AppString.ExpensesMore),
                            { onEvent(ExpensesEvent.PageRequested(content.page.nextCursor)) },
                            enabled = !state.refreshing,
                        )
                }
            }
        if (snapshot != null)
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
        else if (!state.loading && !state.refreshing && state.failure == null)
            item(key = "no-cache") {
                AppText(appString(AppString.ExpensesNoSaved), color = AppColors.muted)
            }
    }
    if (state.dialog != null && !state.readRestricted) ExpenseFilterDialog(state, onEvent)
}
