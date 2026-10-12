package dev.fajar.starter.documents.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.localization.*

@Composable
fun DocumentCancellationDialog(state: DocumentFileState, onEvent: (DocumentFileEvent) -> Unit) {
    AppDialog(
        appString(AppString.DocumentCancelUpload),
        appString(AppString.Close),
        { onEvent(DocumentFileEvent.CancellationDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "reason") {
                AppTextField(
                    appString(AppString.DocumentCancelReason),
                    state.cancellationReason.orEmpty(),
                    { onEvent(DocumentFileEvent.CancellationReasonChanged(it)) },
                    enabled = !state.working,
                    maxLines = 3,
                )
            }
            state.failure?.let { failure ->
                item(key = "failure") { AppFeedback(failureText(failure)) }
            }
            item(key = "actions") {
                AppButton(
                    appString(AppString.DocumentCancelUpload),
                    { onEvent(DocumentFileEvent.CancellationConfirmed) },
                    enabled = !state.working,
                )
            }
        }
    }
}
