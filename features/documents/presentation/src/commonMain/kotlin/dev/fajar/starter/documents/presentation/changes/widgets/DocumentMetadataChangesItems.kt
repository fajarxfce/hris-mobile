package dev.fajar.starter.documents.presentation.changes.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.domain.metadata.commands.entities.DocumentMetadataCommandOutcome
import dev.fajar.starter.documents.presentation.changes.*
import dev.fajar.starter.localization.*

internal fun LazyListScope.documentMetadataChangesItems(
    state: DocumentMetadataChangesState,
    onEvent: (DocumentMetadataChangesEvent) -> Unit,
) {
    if (state.after != null || state.copies?.page?.nextDocumentId != null)
        item(key = "paging") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextButton(
                    appString(AppString.DirectoryFirstPage),
                    { onEvent(DocumentMetadataChangesEvent.PageRequested(null)) },
                    enabled = !state.loadingCopies && state.after != null,
                )
                state.copies?.page?.nextDocumentId?.let { cursor ->
                    AppTextButton(
                        appString(AppString.DirectoryNextPage),
                        { onEvent(DocumentMetadataChangesEvent.PageRequested(cursor)) },
                        enabled = !state.loadingCopies,
                    )
                }
            }
        }
    if (
        !state.loadingCommands &&
            !state.loadingCopies &&
            state.failure == null &&
            state.records.isEmpty() &&
            state.drafts.isEmpty() &&
            state.unavailable.isEmpty()
    )
        item(key = "empty") {
            AppText(appString(AppString.DocumentMetadataChangesEmpty), color = AppColors.muted)
        }
    items(state.unavailable, key = { "restricted-${it.documentId}" }) { restriction ->
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(appString(AppString.DocumentReadRestricted))
                AppTextButton(
                    appString(AppString.TryAgain),
                    {
                        onEvent(
                            DocumentMetadataChangesEvent.RecoveryRequested(restriction.documentId)
                        )
                    },
                    modifier = Modifier.testTag("metadata-recover-${restriction.documentId}"),
                )
            }
        }
    }
    if (state.records.isNotEmpty())
        item(key = "commands") { AppText(appString(AppString.DocumentMetadataSavedCorrections)) }
    items(state.records, key = { "command-${it.command.change.operationId}" }) { record ->
        AppCard(padding = 0.dp) {
            AppListRow(
                record.command.documentTitle,
                {
                    onEvent(
                        DocumentMetadataChangesEvent.EditorRequested(
                            record.command.change.documentId
                        )
                    )
                },
                modifier =
                    Modifier.testTag("metadata-correction-${record.command.change.documentId}"),
                supportingText =
                    appString(
                        when (record.outcome) {
                            DocumentMetadataCommandOutcome.Pending ->
                                AppString.DocumentMetadataPending
                            is DocumentMetadataCommandOutcome.Unconfirmed ->
                                AppString.DocumentMetadataUnconfirmed
                            is DocumentMetadataCommandOutcome.Rejected ->
                                AppString.DocumentMetadataRejected
                            is DocumentMetadataCommandOutcome.Confirmed ->
                                AppString.DocumentMetadataConfirmed
                        }
                    ),
            )
        }
    }
    if (state.drafts.isNotEmpty())
        item(key = "drafts") { AppText(appString(AppString.DocumentMetadataDrafts)) }
    items(state.drafts, key = { "draft-${it.head.id}" }) { copy ->
        AppCard(padding = 0.dp) {
            AppListRow(
                copy.head.title,
                { onEvent(DocumentMetadataChangesEvent.EditorRequested(copy.head.id)) },
                modifier = Modifier.testTag("metadata-draft-${copy.head.id}"),
                supportingText = appString(AppString.DocumentMetadataDraftSaved),
            )
        }
    }
}
