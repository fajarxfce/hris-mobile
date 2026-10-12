package dev.fajar.starter.documents.presentation.download.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.presentation.download.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.Checkmark

@Composable
fun DocumentDownloadPage(
    state: DocumentDownloadState,
    list: LazyListState,
    onEvent: (DocumentDownloadEvent) -> Unit,
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
                        { onEvent(DocumentDownloadEvent.BackRequested) },
                    )
                },
            )
        },
    ) {
        if (state.loading || state.work != null)
            item(key = "loading") { AppLoading(compact = true) }
        val failure = state.restriction ?: state.failure
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(DocumentDownloadEvent.VerificationRequested) },
                        )
                    else if (state.restriction != null || state.file == null)
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(DocumentDownloadEvent.RetryRequested) },
                            enabled = !state.busy,
                        )
                }
            }
        state.item?.let { file ->
            item(key = "document") {
                AppCard(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    AppText(
                        state.file?.draft?.download?.evidence?.title ?: file.name,
                        style = FluentTheme.typography.subtitle,
                    )
                    AppText(file.name)
                    AppText(
                        file.description,
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                    AppText(appString(file.status), color = AppColors.muted)
                    file.progress?.let { progress -> AppLinearProgress(progress) }
                }
            }
        }
        if (state.work != null)
            item(key = "working") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(
                        appString(
                            when (state.work) {
                                DocumentDownloadWork.Downloading -> AppString.DocumentDownloading
                                DocumentDownloadWork.Saving -> AppString.DocumentSaving
                                DocumentDownloadWork.Revalidating ->
                                    AppString.DocumentCheckingAccess
                            }
                        ),
                        color = AppColors.muted,
                    )
                    AppTextButton(
                        appString(
                            if (state.work == DocumentDownloadWork.Downloading)
                                AppString.DocumentPauseDownload
                            else AppString.Cancel
                        ),
                        { onEvent(DocumentDownloadEvent.StopRequested) },
                    )
                }
            }
        if (state.canDownload)
            item(key = "download") {
                AppButton(
                    appString(AppString.DocumentResumeDownload),
                    { onEvent(DocumentDownloadEvent.DownloadRequested) },
                )
            }
        if (state.canSave)
            item(key = "save") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        appString(AppString.DocumentSaveCopy),
                        { onEvent(DocumentDownloadEvent.SaveRequested) },
                    )
                    AppText(
                        appString(AppString.DocumentSaveConnection),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
            }
        if (state.saved)
            item(key = "saved") {
                Row(
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon(Icons.Regular.Checkmark, tint = AppColors.accent)
                    AppText(appString(AppString.DocumentCopySaved))
                }
            }
    }
}
