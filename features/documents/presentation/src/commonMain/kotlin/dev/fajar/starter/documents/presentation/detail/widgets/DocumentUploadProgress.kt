package dev.fajar.starter.documents.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.documents.presentation.metadata.DocumentMetadataDetails
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun DocumentUploadProgress(state: DocumentFileState, onEvent: (DocumentFileEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.item?.let { file ->
            AppText(appString(file.status), style = FluentTheme.typography.subtitle)
            file.progress?.let { AppLinearProgress(it) }
            if (file.status == AppString.DocumentRejected)
                AppText(appString(AppString.DocumentRejectionInfo), color = AppColors.muted)
            if (file.status == AppString.DocumentValidationFailed)
                AppText(appString(AppString.DocumentScanUnavailable), color = AppColors.muted)
        }
        state.file?.upload?.intent?.input?.metadata?.let { DocumentMetadataDetails(it) }
        state.file?.upload?.failure?.let { failure ->
            if (state.failure == null && state.rejectedFailure == null)
                AppFeedback(failureText(failure))
        }
        if (state.writeAllowed)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.canResume)
                    AppTextButton(
                        appString(AppString.DocumentResume),
                        { onEvent(DocumentFileEvent.ResumeRequested) },
                    )
                if (state.canRetryValidation)
                    AppTextButton(
                        appString(AppString.DocumentRetryValidation),
                        { onEvent(DocumentFileEvent.ValidationRetryRequested) },
                    )
                if (state.canCancel)
                    AppTextButton(
                        appString(AppString.DocumentCancelUpload),
                        { onEvent(DocumentFileEvent.CancellationRequested) },
                    )
            }
    }
}
