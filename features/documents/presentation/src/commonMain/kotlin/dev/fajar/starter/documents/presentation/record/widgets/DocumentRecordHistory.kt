package dev.fajar.starter.documents.presentation.record.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.presentation.metadata.DocumentMetadataDetails
import dev.fajar.starter.documents.presentation.record.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

internal fun LazyListScope.documentRecordHistory(
    state: DocumentRecordState,
    view: DocumentMetadataView,
    onEvent: (DocumentRecordEvent) -> Unit,
) {
    val snapshot = view.snapshot
    item("history-title") {
        AppText(
            appString(AppString.DocumentMetadataHistory),
            style = FluentTheme.typography.subtitle,
        )
    }
    if (state.after != null || snapshot.history.nextCursor != null)
        item("paging") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextButton(
                    appString(AppString.DirectoryFirstPage),
                    { onEvent(DocumentRecordEvent.HistoryRequested(null)) },
                    enabled = state.canPage && state.after != null,
                )
                snapshot.history.nextCursor?.let { cursor ->
                    AppTextButton(
                        appString(AppString.DirectoryNextPage),
                        { onEvent(DocumentRecordEvent.HistoryRequested(cursor)) },
                        enabled = state.canPage,
                    )
                }
            }
        }
    if (!snapshot.completePage)
        item("history-unavailable") {
            AppText(appString(AppString.DocumentHistoryNotSaved), color = AppColors.muted)
        }
    items(snapshot.history.items, key = { "metadata-${it.version}" }) { row ->
        MetadataHistoryCard(row, view.access.accountId)
    }
}

@Composable
private fun MetadataHistoryCard(row: DocumentMetadataRevision, accountId: String) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText(
                if (row.version == 0L) appString(AppString.DocumentMetadataInitial)
                else appString(AppString.DocumentMetadataRevision, row.version)
            )
            AppText(
                formatDate(row.recordedAt.toEpochMilliseconds(), appLanguageTag()),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
            val actor = row.actorId
            AppText(
                when (actor) {
                    null -> appString(AppString.DocumentHistoryAuthorUnknown)
                    accountId -> appString(AppString.DocumentHistoryByYou)
                    else -> appString(AppString.DocumentHistoryByOther)
                },
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
            DocumentMetadataDetails(row.metadata)
            row.reason?.let { AppText(it) }
        }
    }
}
