package dev.fajar.starter.documents.presentation.changes.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.changes.*
import dev.fajar.starter.documents.presentation.changes.widgets.documentMetadataChangesItems
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun DocumentMetadataChangesPage(
    state: DocumentMetadataChangesState,
    list: LazyListState,
    onEvent: (DocumentMetadataChangesEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 12.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.DocumentMetadataChanges),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(DocumentMetadataChangesEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(DocumentMetadataChangesEvent.RefreshRequested) },
                        enabled = !state.refreshing,
                        loading = state.refreshing,
                    )
                },
            )
        },
    ) {
        item(key = "hint") {
            AppText(appString(AppString.DocumentMetadataChangesHint), color = AppColors.muted)
        }
        if (state.loadingCommands || state.loadingCopies || state.refreshing)
            item(key = "loading") { AppLoading(compact = true) }
        val failure = state.restriction ?: state.failure
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(DocumentMetadataChangesEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(DocumentMetadataChangesEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                        )
                }
            }
        if (state.restriction == null) documentMetadataChangesItems(state, onEvent)
    }
}
