package dev.fajar.starter.documents.presentation.editor.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.domain.entities.DocumentCategory
import dev.fajar.starter.documents.presentation.editor.*
import dev.fajar.starter.documents.presentation.metadata.label
import dev.fajar.starter.localization.*

@Composable
fun DocumentMetadataEditorFields(
    state: DocumentMetadataEditorState,
    onEvent: (DocumentMetadataEditorEvent) -> Unit,
) {
    val local = state.persistence ?: return
    val input = local.input
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppCard(padding = 0.dp) {
            AppListRow(
                appString(AppString.DocumentBusinessCategory),
                { onEvent(DocumentMetadataEditorEvent.CategoryRequested) },
                supportingText = appString(input.category.label()),
                enabled = state.canEdit,
            )
        }
        AppTextField(
            appString(AppString.DocumentNumber),
            input.identityNumber,
            { onEvent(DocumentMetadataEditorEvent.NumberChanged(it)) },
            enabled = state.canEdit,
        )
        AppTextField(
            appString(AppString.DocumentIssuingCountry),
            input.issuingCountry,
            { onEvent(DocumentMetadataEditorEvent.CountryChanged(it)) },
            enabled = state.canEdit,
        )
        AppText(appString(AppString.DocumentCountryHint), color = AppColors.muted)
        AppTextField(
            appString(AppString.DocumentIssuedOn),
            input.issuedOn,
            { onEvent(DocumentMetadataEditorEvent.IssuedOnChanged(it)) },
            enabled = state.canEdit,
        )
        AppTextField(
            appString(AppString.DocumentValidUntil),
            input.validUntil,
            { onEvent(DocumentMetadataEditorEvent.ValidUntilChanged(it)) },
            enabled = state.canEdit,
        )
        AppText(appString(AppString.DocumentBusinessDatesHint), color = AppColors.muted)
        AppTextField(
            appString(AppString.DocumentMetadataReason),
            local.reason,
            { onEvent(DocumentMetadataEditorEvent.ReasonChanged(it)) },
            enabled = state.canEdit,
            maxLines = 3,
        )
    }
}

@Composable
fun DocumentMetadataEditorCategory(
    state: DocumentMetadataEditorState,
    onEvent: (DocumentMetadataEditorEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.DocumentBusinessCategory),
        appString(AppString.Close),
        { onEvent(DocumentMetadataEditorEvent.CategoryDismissed) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(DocumentCategory.entries, key = { it.name }) { category ->
                AppRadioRow(
                    appString(category.label()),
                    state.persistence?.input?.category == category,
                    { onEvent(DocumentMetadataEditorEvent.CategorySelected(category)) },
                    enabled = state.canEdit,
                )
            }
        }
    }
}
