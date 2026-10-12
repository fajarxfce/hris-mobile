package dev.fajar.starter.documents.presentation.files.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.files.*
import dev.fajar.starter.documents.presentation.files.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.Document

@Composable
fun DocumentFilesPage(
    state: DocumentFilesState,
    list: LazyListState,
    onEvent: (DocumentFilesEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 12.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.Documents),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(DocumentFilesEvent.BackRequested) },
                    )
                },
                actions = {
                    if (state.content?.files?.isNotEmpty() == true)
                        AppIconButton(
                            Icons.Regular.Add,
                            appString(AppString.DocumentAddFile),
                            { onEvent(DocumentFilesEvent.ImportRequested) },
                            enabled = state.canImport,
                        )
                },
            )
        },
    ) {
        if (state.loading || state.revalidating) item(key = "loading") { AppLoading() }
        val failure = state.restriction ?: state.failure ?: state.readFailure
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(DocumentFilesEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(DocumentFilesEvent.RetryRequested) },
                            enabled = !state.busy,
                        )
                }
            }
        if (state.content != null) {
            item(key = "published") {
                AppActionTile(
                    appString(AppString.DocumentPublished),
                    appString(AppString.DocumentPublishedDescription),
                    Icons.Regular.Document,
                    { onEvent(DocumentFilesEvent.CatalogRequested) },
                    enabled = !state.busy,
                )
            }
            item(key = "local-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        appString(AppString.DocumentLocalFiles),
                        style = FluentTheme.typography.subtitle,
                    )
                    AppText(
                        appString(AppString.DocumentFileTypes),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
            }
            if (state.importing)
                item(key = "importing") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLoading(compact = true)
                        AppText(appString(AppString.DocumentImporting), color = AppColors.muted)
                    }
                }
            if (state.content.files.isEmpty() && !state.importing)
                item(key = "empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppText(appString(AppString.DocumentNoLocalFiles), color = AppColors.muted)
                        if (state.canImport)
                            AppButton(
                                appString(AppString.DocumentAddFile),
                                { onEvent(DocumentFilesEvent.ImportRequested) },
                            )
                    }
                }
            items(state.items, key = { it.id }) { file ->
                DocumentFileRow(file, !state.busy, onEvent)
            }
        }
    }
    if (state.removal != null) DocumentRemovalDialog(state, onEvent)
}
