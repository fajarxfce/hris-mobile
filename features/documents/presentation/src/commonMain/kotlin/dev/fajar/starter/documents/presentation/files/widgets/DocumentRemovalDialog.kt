package dev.fajar.starter.documents.presentation.files.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.presentation.files.*
import dev.fajar.starter.localization.*

@Composable
fun DocumentRemovalDialog(state: DocumentFilesState, onEvent: (DocumentFilesEvent) -> Unit) {
    AppDialog(
        appString(AppString.DocumentRemoveLocal),
        appString(AppString.Close),
        { onEvent(DocumentFilesEvent.RemovalDismissed) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppText(state.removal?.draft?.declaration?.name.orEmpty())
            AppText(appString(AppString.DocumentRemoveExplanation))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(
                    appString(AppString.DocumentRemoveLocal),
                    { onEvent(DocumentFilesEvent.RemovalConfirmed) },
                    enabled = !state.removing,
                )
                AppTextButton(
                    appString(AppString.Cancel),
                    { onEvent(DocumentFilesEvent.RemovalDismissed) },
                    enabled = !state.removing,
                )
            }
        }
    }
}
