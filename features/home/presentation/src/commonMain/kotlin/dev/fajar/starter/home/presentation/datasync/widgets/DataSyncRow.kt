package dev.fajar.starter.home.presentation.datasync.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.datasync.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun DataSyncRow(state: DataSyncRowState, onEvent: (DataSyncEvent) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .testTag("data-sync-${state.dataSet.name}")
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(appString(state.dataSet.title), style = FluentTheme.typography.bodyStrong)
            if (state.status != null)
                AppText(
                    state.status.lastCompletedAt?.let {
                        formatDate(it.toEpochMilliseconds(), appLanguageTag())
                    } ?: appString(AppString.DataSyncNotCompleted),
                    color = AppColors.muted,
                )
            else if (state.loading) AppText(appString(AppString.Loading), color = AppColors.muted)
            when (state.progress) {
                DataSyncProgress.Queued ->
                    AppText(appString(AppString.DataSyncWaiting), color = AppColors.muted)
                DataSyncProgress.Running ->
                    AppText(appString(AppString.DataSyncRunning), color = AppColors.accent)
                DataSyncProgress.Incomplete ->
                    AppText(appString(AppString.DataSyncIncomplete), color = AppColors.muted)
                DataSyncProgress.Idle -> Unit
            }
            state.failure?.let { failure ->
                AppText(
                    failureText(failure),
                    color = AppColors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        AppIconButton(
            Icons.Regular.ArrowSync,
            appString(AppString.DataSyncDataset, appString(state.dataSet.title)),
            { onEvent(DataSyncEvent.DatasetRefreshRequested(state.dataSet)) },
            loading = state.syncing,
        )
    }
}
