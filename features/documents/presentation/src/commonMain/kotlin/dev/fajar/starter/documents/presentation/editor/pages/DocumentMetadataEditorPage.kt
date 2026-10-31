package dev.fajar.starter.documents.presentation.editor.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.editor.*
import dev.fajar.starter.documents.presentation.editor.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun DocumentMetadataEditorPage(
    state: DocumentMetadataEditorState,
    list: LazyListState,
    onEvent: (DocumentMetadataEditorEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 16.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.DocumentMetadataEdit),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(DocumentMetadataEditorEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(DocumentMetadataEditorEvent.RefreshRequested) },
                        enabled = !state.refreshing && state.work == DocumentMetadataEditorWork.NONE,
                    )
                },
            )
        },
        bottomBar = {
            if (state.ready && state.command == null && state.persistence != null)
                AppActionBar {
                    AppText(
                        appString(
                            if (state.persistence.saved) AppString.DocumentMetadataDraftSaved
                            else AppString.DocumentMetadataDraftSaving
                        ),
                        modifier = Modifier.testTag("metadata-draft-status"),
                        color = AppColors.muted,
                    )
                    AppButton(
                        appString(AppString.DocumentMetadataSave),
                        { onEvent(DocumentMetadataEditorEvent.ConfirmRequested) },
                        enabled = state.canConfirm,
                        modifier = Modifier.fillMaxWidth().testTag("metadata-save"),
                    )
                }
        },
    ) {
        if (state.refreshing || state.initializing || state.work != DocumentMetadataEditorWork.NONE)
            item("progress") { AppLoading(compact = true) }
        val failure = state.restriction ?: state.persistence?.failure ?: state.failure
        if (failure != null)
            item("feedback") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(DocumentMetadataEditorEvent.VerificationRequested) },
                        )
                    else if (state.persistence?.failure != null) {
                        if (failure.code == "client.document_working_copy_changed")
                            AppTextButton(
                                appString(AppString.DocumentMetadataReload),
                                { onEvent(DocumentMetadataEditorEvent.ReloadRequested) },
                                enabled = state.idle,
                            )
                        else
                            AppTextButton(
                                appString(AppString.DocumentMetadataRetrySave),
                                { onEvent(DocumentMetadataEditorEvent.RetryLocalSave) },
                                enabled = state.idle,
                            )
                    } else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(DocumentMetadataEditorEvent.RefreshRequested) },
                            enabled =
                                !state.refreshing && state.work == DocumentMetadataEditorWork.NONE,
                        )
                }
            }
        if (state.ready) {
            val command = state.command
            if (command != null)
                item("command") { DocumentMetadataCommandPanel(state, command, onEvent) }
            else
                state.persistence?.let { local ->
                    item("draft-header") { AppText(local.stored.head.title) }
                    if (state.stale)
                        item("stale") { AppFeedback(appString(AppString.DocumentMetadataStale)) }
                    item("fields") { DocumentMetadataEditorFields(state, onEvent) }
                    item("actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.stale)
                                AppTextButton(
                                    appString(AppString.DocumentMetadataRestart),
                                    { onEvent(DocumentMetadataEditorEvent.RestartRequested) },
                                    enabled = state.idle && state.mayWrite,
                                )
                            AppTextButton(
                                appString(AppString.DocumentMetadataDiscard),
                                { onEvent(DocumentMetadataEditorEvent.DiscardRequested) },
                                enabled = state.idle,
                            )
                        }
                    }
                }
        }
    }
    if (state.choosingCategory) DocumentMetadataEditorCategory(state, onEvent)
    if (state.dialog != null) DocumentMetadataEditorDialog(state, onEvent)
}
