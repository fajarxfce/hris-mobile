package dev.fajar.starter.documents.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.domain.entities.DocumentClassification
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun DocumentUploadFormContent(state: DocumentFileState, onEvent: (DocumentFileEvent) -> Unit) {
    state.form?.let { form ->
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            AppTextField(
                appString(AppString.DocumentUploadTitle),
                form.title,
                { onEvent(DocumentFileEvent.TitleChanged(it)) },
                enabled = !state.working,
            )
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppSelectField(
                    appString(AppString.Employment),
                    form.employeeNumber ?: appString(AppString.ChooseEmployment),
                    { onEvent(DocumentFileEvent.EmploymentRequested) },
                    enabled = state.canWrite,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(
                    appString(AppString.DocumentClassification),
                    style = FluentTheme.typography.bodyStrong,
                )
                AppRadioRow(
                    appString(AppString.DocumentPersonal),
                    form.classification == DocumentClassification.PERSONAL,
                    {
                        onEvent(
                            DocumentFileEvent.ClassificationChanged(DocumentClassification.PERSONAL)
                        )
                    },
                    enabled = !state.working,
                )
                AppRadioRow(
                    appString(AppString.DocumentReceipt),
                    form.classification == DocumentClassification.RECEIPT,
                    {
                        onEvent(
                            DocumentFileEvent.ClassificationChanged(DocumentClassification.RECEIPT)
                        )
                    },
                    enabled = !state.working,
                )
            }
            if (form.preparation.employments.metadataSupported)
                DocumentMetadataFields(state, onEvent)
            AppTextField(
                appString(AppString.DocumentUploadReason),
                form.reason,
                { onEvent(DocumentFileEvent.ReasonChanged(it)) },
                enabled = !state.working,
                maxLines = 3,
            )
        }
    }
}
