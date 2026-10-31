package dev.fajar.starter.documents.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.documents.presentation.metadata.label
import dev.fajar.starter.localization.*

@Composable
fun DocumentMetadataFields(state: DocumentFileState, onEvent: (DocumentFileEvent) -> Unit) {
    val metadata = state.form?.metadata ?: return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppSelectField(
                appString(AppString.DocumentBusinessCategory),
                appString(metadata.category.label()),
                { onEvent(DocumentFileEvent.CategoryRequested) },
                enabled = state.canWrite,
            )
        }
        AppTextField(
            appString(AppString.DocumentNumber),
            metadata.identityNumber,
            { onEvent(DocumentFileEvent.DocumentNumberChanged(it)) },
            enabled = state.canWrite,
        )
        AppTextField(
            appString(AppString.DocumentIssuingCountry),
            metadata.issuingCountry,
            { onEvent(DocumentFileEvent.IssuingCountryChanged(it)) },
            enabled = state.canWrite,
        )
        AppText(appString(AppString.DocumentCountryHint), color = AppColors.muted)
        AppTextField(
            appString(AppString.DocumentIssuedOn),
            metadata.issuedOn,
            { onEvent(DocumentFileEvent.IssueDateChanged(it)) },
            enabled = state.canWrite,
        )
        AppTextField(
            appString(AppString.DocumentValidUntil),
            metadata.validUntil,
            { onEvent(DocumentFileEvent.ValidUntilChanged(it)) },
            enabled = state.canWrite,
        )
        AppText(appString(AppString.DocumentBusinessDatesHint), color = AppColors.muted)
    }
}
