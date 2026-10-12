package dev.fajar.starter.documents.presentation.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.detail.*
import dev.fajar.starter.documents.presentation.detail.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Document

@Composable
fun DocumentFilePage(
    state: DocumentFileState,
    list: LazyListState,
    employments: LazyListState,
    onEvent: (DocumentFileEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 16.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.DocumentDetails),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(DocumentFileEvent.BackRequested) },
                    )
                },
                actions = {
                    if (state.file != null && state.writeAllowed) {
                        if (state.file.upload == null)
                            AppIconButton(
                                Icons.Regular.ArrowSync,
                                appString(AppString.DocumentRefreshForm),
                                { onEvent(DocumentFileEvent.FormReloadRequested) },
                                enabled = state.canWrite,
                            )
                        else
                            AppIconButton(
                                Icons.Regular.ArrowSync,
                                appString(AppString.DocumentCheckStatus),
                                { onEvent(DocumentFileEvent.StatusRequested) },
                                enabled = state.canCheck,
                            )
                    }
                },
            )
        },
    ) {
        if (state.loading || state.formLoading || state.working || state.revalidating)
            item(key = "loading") { AppLoading(compact = true) }
        val failure = state.restriction ?: state.failure ?: state.readFailure ?: state.formFailure
        if (failure != null && state.cancellationReason == null && !state.choosingEmployment)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(DocumentFileEvent.VerificationRequested) },
                        )
                    else if (state.file == null || state.restriction != null)
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(DocumentFileEvent.RetryRequested) },
                            enabled = !state.busy,
                        )
                }
            }
        state.item?.let { file ->
            item(key = "file") {
                AppCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(Icons.Regular.Document, null)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AppText(file.name, style = FluentTheme.typography.bodyStrong)
                            AppText(
                                file.description,
                                color = AppColors.muted,
                                style = FluentTheme.typography.caption,
                            )
                        }
                    }
                }
            }
        }
        if (state.form != null && state.item != null && state.writeAllowed)
            item(key = "form") { DocumentUploadFormContent(state, onEvent) }
        if (state.file?.upload != null)
            item(key = "upload") { DocumentUploadProgress(state, onEvent) }
    }
    if (state.choosingEmployment) DocumentEmploymentDialog(state, employments, onEvent)
    if (state.cancellationReason != null) DocumentCancellationDialog(state, onEvent)
}
