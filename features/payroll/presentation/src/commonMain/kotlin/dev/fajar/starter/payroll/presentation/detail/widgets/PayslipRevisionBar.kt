package dev.fajar.starter.payroll.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.presentation.detail.*
import io.github.composefluent.FluentTheme

@Composable
fun PayslipRevisionBar(state: PayslipState, onEvent: (PayslipEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        AppPeriodNavigation(
            appString(AppString.PayslipPreviousRevision),
            appString(AppString.PayslipNextRevision),
            { onEvent(PayslipEvent.PreviousRevisionRequested) },
            { onEvent(PayslipEvent.NextRevisionRequested) },
            Modifier.fillMaxWidth(),
            previousEnabled = state.canChoosePrevious,
            nextEnabled = state.canChooseNext,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    appString(
                        AppString.PayslipRevisionPosition,
                        state.displayedRevision.toString(),
                        state.currentRevision.toString(),
                    ),
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = FluentTheme.typography.bodyStrong,
                )
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (state.revisionLoading) AppProgressRing(appString(AppString.Loading))
                }
            }
        }
        if (state.selectedRevision != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    appString(AppString.PayslipHistoricalRevision),
                    Modifier.weight(1f),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
                AppTextButton(
                    appString(AppString.PayslipCurrentRevision),
                    { onEvent(PayslipEvent.CurrentRevisionRequested) },
                    enabled = state.canChooseRevision,
                )
            }
        }
    }
}
