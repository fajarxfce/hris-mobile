package dev.fajar.starter.payroll.presentation.overview.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.presentation.overview.*
import dev.fajar.starter.payroll.presentation.overview.widgets.*
import dev.fajar.starter.payroll.presentation.widgets.PayslipReadFeedback
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun PayslipsPage(state: PayslipsState, list: LazyListState, onEvent: (PayslipsEvent) -> Unit) {
    AppLazyPage(
        state = list,
        maxWidth = 720.dp,
        spacing = 12.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.Payslips),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(PayslipsEvent.BackRequested) },
                        )
                    },
                    actions = {
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(PayslipsEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                            loading = state.refreshing,
                        )
                    },
                )
                PayslipYearBar(state, onEvent)
                state.failure?.let {
                    PayslipReadFeedback(it, state.needsVerification, state.refreshing) {
                        onEvent(PayslipsEvent.VerificationRequested)
                    }
                }
            }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = state.content != null) }
        if (state.syncPending)
            item(key = "sync-pending") {
                AppText(appString(AppString.SyncPending), color = AppColors.muted)
            }
        val snapshot = state.content?.takeUnless { state.readRestricted }
        val overview = snapshot?.value
        if (overview != null && overview.page.items.isEmpty())
            item(key = "empty") {
                AppText(appString(AppString.PayslipsEmpty), color = AppColors.muted)
            }
        items(overview?.page?.items.orEmpty(), key = { it.id }) {
            PayslipRow(it) { onEvent(PayslipsEvent.PayslipSelected(it.id)) }
        }
        if (
            overview != null &&
                (overview.selection.after != null || overview.page.nextCursor != null)
        )
            item(key = "pages") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (overview.selection.after != null)
                        AppTextButton(
                            appString(AppString.PayslipsFirstPage),
                            { onEvent(PayslipsEvent.PageRequested(null)) },
                            enabled = !state.readRestricted,
                        )
                    if (overview.page.nextCursor != null)
                        AppTextButton(
                            appString(AppString.PayslipsMore),
                            { onEvent(PayslipsEvent.PageRequested(overview.page.nextCursor)) },
                            enabled = !state.readRestricted,
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
                AppText(appString(AppString.PayslipsNoSaved), color = AppColors.muted)
            }
    }
}
