package dev.fajar.starter.documents.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.domain.entities.DocumentCategory
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.documents.presentation.metadata.label
import dev.fajar.starter.localization.*

@Composable
fun DocumentCategoryDialog(state: DocumentFileState, onEvent: (DocumentFileEvent) -> Unit) {
    AppDialog(
        appString(AppString.DocumentBusinessCategory),
        appString(AppString.Close),
        { onEvent(DocumentFileEvent.CategoryDismissed) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(DocumentCategory.entries, key = { it.name }) { category ->
                AppRadioRow(
                    appString(category.label()),
                    state.form?.metadata?.category == category,
                    { onEvent(DocumentFileEvent.CategorySelected(category)) },
                    enabled = state.canWrite,
                )
            }
        }
    }
}
