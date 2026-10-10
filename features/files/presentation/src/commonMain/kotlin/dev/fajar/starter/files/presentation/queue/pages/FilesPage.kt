package dev.fajar.starter.files.presentation.queue.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.files.presentation.queue.*
import dev.fajar.starter.files.presentation.queue.widgets.TransferItem
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun FilesPage(state: FilesState, onEvent: (FilesEvent) -> Unit) {
    AppLazyPage {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppHeading(appString(AppString.Files), style = FluentTheme.typography.title)
                AppButton(
                    appString(AppString.Back),
                    { onEvent(FilesEvent.BackRequested) },
                    primary = false,
                )
            }
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppButton(
                    appString(AppString.UploadSample),
                    { onEvent(FilesEvent.UploadRequested) },
                    enabled = state.canUpload,
                    loading = state.importing,
                )
                AppButton(
                    appString(AppString.DownloadSample),
                    { onEvent(FilesEvent.DownloadRequested) },
                    enabled = state.canDownload,
                    loading = state.downloading,
                    primary = false,
                )
            }
        }
        if (state.error != null) item { AppFeedback(failureText(state.error)) }
        if (state.loading) item { AppLoading() }
        else if (state.rows.isEmpty()) item { AppText(appString(AppString.EmptyTransfers)) }
        items(state.rows, key = { it.id }) { row ->
            TransferItem(row, state.changingId == null, onEvent)
        }
    }
}
