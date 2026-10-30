package dev.fajar.starter.documents.presentation.editor.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.presentation.editor.*
import dev.fajar.starter.documents.presentation.metadata.DocumentMetadataDetails
import dev.fajar.starter.localization.*

@Composable
fun DocumentMetadataCommandPanel(
    state: DocumentMetadataEditorState,
    record: DocumentMetadataCommandRecord,
    onEvent: (DocumentMetadataEditorEvent) -> Unit,
) {
    AppCard(Modifier.fillMaxWidth().testTag("metadata-command")) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppText(record.command.documentTitle)
            AppText(
                appString(
                    when (record.outcome) {
                        DocumentMetadataCommandOutcome.Pending -> AppString.DocumentMetadataPending
                        is DocumentMetadataCommandOutcome.Unconfirmed ->
                            AppString.DocumentMetadataUnconfirmed
                        is DocumentMetadataCommandOutcome.Rejected ->
                            AppString.DocumentMetadataRejected
                        is DocumentMetadataCommandOutcome.Confirmed ->
                            AppString.DocumentMetadataConfirmed
                    }
                )
            )
            DocumentMetadataDetails(record.command.change.metadata)
            AppText(record.command.change.reason)
            when (val outcome = record.outcome) {
                is DocumentMetadataCommandOutcome.Rejected ->
                    AppFeedback(failureText(outcome.failure))
                is DocumentMetadataCommandOutcome.Unconfirmed ->
                    outcome.failure?.let { AppFeedback(failureText(it)) }
                else -> Unit
            }
            if (
                record.outcome is DocumentMetadataCommandOutcome.Pending ||
                    record.outcome is DocumentMetadataCommandOutcome.Unconfirmed
            )
                AppButton(
                    appString(AppString.DocumentMetadataRetry),
                    { onEvent(DocumentMetadataEditorEvent.RetryDelivery) },
                    enabled = state.idle && state.mayWrite,
                    modifier = Modifier.testTag("metadata-retry"),
                )
            if (record.outcome !is DocumentMetadataCommandOutcome.Unconfirmed)
                AppTextButton(
                    appString(
                        when (record.outcome) {
                            DocumentMetadataCommandOutcome.Pending ->
                                AppString.DocumentMetadataCancel
                            is DocumentMetadataCommandOutcome.Confirmed ->
                                AppString.DocumentMetadataDone
                            else -> AppString.DocumentMetadataReview
                        }
                    ),
                    { onEvent(DocumentMetadataEditorEvent.ReviewRequested) },
                    enabled = state.canReview,
                    modifier = Modifier.testTag("metadata-review"),
                )
        }
    }
}
