package dev.fajar.starter.home.presentation.datasync.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.datasync.*
import dev.fajar.starter.home.presentation.datasync.widgets.DataSyncRow
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Dismiss

@Composable
fun DataSyncPage(state: DataSyncState, onEvent: (DataSyncEvent) -> Unit) {
    AppLazyPage(
        maxWidth = 640.dp,
        spacing = 0.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                title = appString(AppString.DataAndSync),
                subtitle = state.companyName,
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(DataSyncEvent.BackRequested) },
                    )
                },
                actions = {
                    if (state.syncing)
                        AppIconButton(
                            Icons.Regular.Dismiss,
                            appString(AppString.DataSyncCancel),
                            { onEvent(DataSyncEvent.CancelRequested) },
                        )
                    else
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.DataSyncAll),
                            { onEvent(DataSyncEvent.RefreshRequested) },
                            enabled = state.rows.isNotEmpty(),
                        )
                },
            )
        },
    ) {
        item(key = "summary") {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(appString(AppString.DataSyncSummary), color = AppColors.muted)
                if (state.needsVerification)
                    AppTextButton(
                        appString(AppString.VerifyAccount),
                        { onEvent(DataSyncEvent.VerificationRequested) },
                    )
                AppText(
                    appString(AppString.DataSyncLastCompleted),
                    style = FluentTheme.typography.bodyStrong,
                )
            }
        }
        items(state.rows, key = { it.dataSet.name }) { row -> DataSyncRow(row, onEvent) }
    }
}
