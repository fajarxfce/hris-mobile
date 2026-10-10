package dev.fajar.starter.files.presentation.queue.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.files.presentation.queue.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.transfers.domain.entities.TransferAction
import io.github.composefluent.FluentTheme

@Composable
fun TransferItem(row: TransferRow, enabled: Boolean, onEvent: (FilesEvent) -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText(row.name, style = FluentTheme.typography.bodyStrong)
            AppText("${appString(row.direction)} · ${appString(row.status)} · ${row.progress}%")
            AppLinearProgress(row.progress / 100f)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (row.canPause)
                    AppButton(
                        appString(AppString.Pause),
                        { onEvent(FilesEvent.TransferChanged(row.id, TransferAction.Pause)) },
                        enabled = enabled,
                        primary = false,
                    )
                if (row.canResume)
                    AppButton(
                        appString(AppString.Resume),
                        { onEvent(FilesEvent.TransferChanged(row.id, TransferAction.Resume)) },
                        enabled = enabled,
                        primary = false,
                    )
                AppButton(
                    appString(AppString.Remove),
                    { onEvent(FilesEvent.TransferChanged(row.id, TransferAction.Remove)) },
                    enabled = enabled,
                    primary = false,
                )
            }
        }
    }
}
