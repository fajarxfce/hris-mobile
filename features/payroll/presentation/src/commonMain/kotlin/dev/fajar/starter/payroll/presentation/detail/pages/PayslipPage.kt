package dev.fajar.starter.payroll.presentation.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.presentation.detail.*
import dev.fajar.starter.payroll.presentation.detail.widgets.*
import dev.fajar.starter.payroll.presentation.widgets.PayslipReadFeedback
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun PayslipPage(state: PayslipState, list: LazyListState, onEvent: (PayslipEvent) -> Unit) {
    AppLazyPage(
        state = list,
        maxWidth = 720.dp,
        spacing = 16.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.PayslipTitle),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(PayslipEvent.BackRequested) },
                        )
                    },
                    actions = {
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(PayslipEvent.RefreshRequested) },
                            enabled = !state.refreshing && !state.saving,
                            loading = state.refreshing,
                        )
                        AppIconButton(
                            Icons.Regular.ArrowDownload,
                            appString(AppString.PayslipSavePdf),
                            { onEvent(PayslipEvent.ExportRequested) },
                            enabled = state.canSave,
                            loading = state.saving,
                        )
                    },
                )
                state.failure?.let {
                    PayslipReadFeedback(
                        it,
                        state.needsVerification,
                        state.refreshing || state.saving,
                    ) {
                        onEvent(PayslipEvent.VerificationRequested)
                    }
                }
                if (state.saved)
                    AppText(
                        appString(AppString.PayslipPdfSaved),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        color = AppColors.accent,
                    )
            }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = state.content != null) }
        if (state.syncPending)
            item(key = "sync-pending") {
                AppText(appString(AppString.SyncPending), color = AppColors.muted)
            }
        val snapshot = state.content?.takeUnless { state.readRestricted }
        if (snapshot != null) {
            val view = snapshot.value
            val payslip = view.payslip
            item(key = "summary") { PayslipSummaryCard(payslip) }
            item(key = "payment") { PayslipPaymentCard(view) }
            item(key = "currency") {
                AppText(
                    appString(AppString.PayslipCurrency, payslip.summary.currency),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
            item(key = "earnings") { PayslipEarningsCard(payslip) }
            item(key = "deductions") { PayslipDeductionsCard(payslip) }
            if (payslip.breakdown.contributions.isNotEmpty())
                item(key = "employer") { PayslipEmployerCard(payslip) }
            item(key = "tax") { PayslipTaxCard(payslip) }
            item(key = "publication") {
                AppText(
                    appString(
                        AppString.PayslipPublished,
                        formatDate(
                            payslip.summary.publishedAt.toEpochMilliseconds(),
                            appLanguageTag(),
                        ),
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
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
            item(key = "empty") {
                AppText(appString(AppString.PayslipsNoSaved), color = AppColors.muted)
            }
    }
    state.exportRevision?.let { revision ->
        if (state.canSave) PayslipExportDialog(revision, onEvent)
    }
}
