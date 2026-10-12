package dev.fajar.starter.documents.presentation.files.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.presentation.files.DocumentFileItem
import dev.fajar.starter.documents.presentation.files.DocumentFilesEvent
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Document

@Composable
fun DocumentFileRow(
    file: DocumentFileItem,
    enabled: Boolean,
    onEvent: (DocumentFilesEvent) -> Unit,
) {
    AppCard(padding = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppListRow(
                file.name,
                { onEvent(DocumentFilesEvent.FileRequested(file.id)) },
                modifier = Modifier.weight(1f),
                supportingText = appString(file.status),
                enabled = enabled && file.openable,
                leading = { AppIcon(Icons.Regular.Document, null) },
            )
            if (file.removable)
                AppIconButton(
                    Icons.Regular.Delete,
                    appString(AppString.DocumentRemoveNamed, file.name),
                    { onEvent(DocumentFilesEvent.RemovalRequested(file.id)) },
                    enabled = enabled,
                    modifier = Modifier.padding(end = 8.dp),
                )
        }
    }
}
