package dev.fajar.starter.documents.presentation.editor.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.presentation.editor.*
import dev.fajar.starter.localization.*

@Composable
fun DocumentMetadataEditorDialog(
    state: DocumentMetadataEditorState,
    onEvent: (DocumentMetadataEditorEvent) -> Unit,
) {
    val dialog = state.dialog ?: return
    val title =
        when (dialog) {
            DocumentMetadataEditorDialog.CONFIRM -> AppString.DocumentMetadataConfirm
            DocumentMetadataEditorDialog.CANCEL_COMMAND -> AppString.DocumentMetadataCancel
            DocumentMetadataEditorDialog.DISCARD -> AppString.DocumentMetadataDiscard
            DocumentMetadataEditorDialog.RESTART -> AppString.DocumentMetadataRestart
            DocumentMetadataEditorDialog.RELOAD -> AppString.DocumentMetadataReload
            DocumentMetadataEditorDialog.LEAVE_UNSAVED -> AppString.DocumentMetadataLeave
        }
    AppDialog(
        appString(title),
        appString(AppString.Close),
        { onEvent(DocumentMetadataEditorEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("explanation") {
                AppText(
                    appString(
                        when (dialog) {
                            DocumentMetadataEditorDialog.CONFIRM ->
                                AppString.DocumentMetadataConfirmHint
                            DocumentMetadataEditorDialog.CANCEL_COMMAND ->
                                AppString.DocumentMetadataCancelHint
                            DocumentMetadataEditorDialog.RELOAD ->
                                AppString.DocumentMetadataReloadHint
                            DocumentMetadataEditorDialog.LEAVE_UNSAVED ->
                                AppString.DocumentMetadataLeaveHint
                            else -> AppString.DocumentMetadataDiscardHint
                        }
                    )
                )
            }
            if (dialog == DocumentMetadataEditorDialog.CONFIRM)
                item("review") { DocumentMetadataEditorFields(state, onEvent) }
            item("confirm") {
                AppButton(
                    appString(title),
                    { onEvent(DocumentMetadataEditorEvent.DialogConfirmed) },
                    modifier = Modifier.testTag("metadata-dialog-confirm"),
                )
            }
        }
    }
}
