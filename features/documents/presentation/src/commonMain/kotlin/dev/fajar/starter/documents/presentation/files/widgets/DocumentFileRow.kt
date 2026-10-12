package dev.fajar.starter.documents.presentation.files.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.domain.entities.LocalDocumentFile
import dev.fajar.starter.documents.presentation.files.DocumentFilesEvent
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Document

@Composable
fun DocumentFileRow(
    file: LocalDocumentFile,
    enabled: Boolean,
    onEvent: (DocumentFilesEvent) -> Unit,
) {
    AppCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(Icons.Regular.Document, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(file.draft.declaration.name, style = FluentTheme.typography.bodyStrong)
                AppText(
                    appString(
                        if (file.sha256 == null) AppString.DocumentImportIncomplete
                        else AppString.DocumentNotUploaded
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
            AppIconButton(
                Icons.Regular.Delete,
                appString(AppString.DocumentRemoveNamed, file.draft.declaration.name),
                { onEvent(DocumentFilesEvent.RemovalRequested(file.draft.id)) },
                enabled = enabled,
            )
        }
    }
}
