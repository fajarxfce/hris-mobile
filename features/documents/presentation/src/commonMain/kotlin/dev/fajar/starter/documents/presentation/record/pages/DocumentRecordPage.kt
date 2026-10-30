package dev.fajar.starter.documents.presentation.record.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.metadata.DocumentMetadataDetails
import dev.fajar.starter.documents.presentation.record.*
import dev.fajar.starter.documents.presentation.record.widgets.documentRecordHistory
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun DocumentRecordPage(
    state: DocumentRecordState,
    list: LazyListState,
    onEvent: (DocumentRecordEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 12.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.DocumentDetails),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(DocumentRecordEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(DocumentRecordEvent.RefreshRequested) },
                        enabled = !state.refreshing,
                    )
                },
            )
        },
    ) {
        if (state.loading || state.refreshing) item("progress") { AppLoading(compact = true) }
        val failure = state.restriction ?: state.failure
        if (failure != null)
            item("failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(DocumentRecordEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(DocumentRecordEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                        )
                }
            }
        val view = state.view
        val snapshot = view?.snapshot
        val head = snapshot?.checkpoint?.head
        if (head != null && state.restriction == null) {
            item("header") {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppText(head.title, style = FluentTheme.typography.subtitle)
                        val metadata = head.metadata
                        if (metadata == null)
                            AppText(
                                appString(AppString.DocumentMetadataUnknown),
                                color = AppColors.muted,
                            )
                        else DocumentMetadataDetails(metadata)
                    }
                }
            }
            if (head.metadataSupported) documentRecordHistory(state, view, onEvent)
        } else if (!state.loading && failure == null)
            item("not-saved") { AppText(appString(AppString.SyncPending), color = AppColors.muted) }
    }
}
