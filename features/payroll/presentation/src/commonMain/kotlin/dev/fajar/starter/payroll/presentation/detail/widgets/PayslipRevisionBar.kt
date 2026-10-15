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
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun PayslipRevisionBar(state: PayslipState, onEvent: (PayslipEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                Icons.Regular.ChevronLeft,
                appString(AppString.PayslipPreviousRevision),
                { onEvent(PayslipEvent.PreviousRevisionRequested) },
                enabled = state.canChoosePrevious,
            )
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
            AppIconButton(
                Icons.Regular.ChevronRight,
                appString(AppString.PayslipNextRevision),
                { onEvent(PayslipEvent.NextRevisionRequested) },
                enabled = state.canChooseNext,
            )
        }
        if (state.selectedRevision != null) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
