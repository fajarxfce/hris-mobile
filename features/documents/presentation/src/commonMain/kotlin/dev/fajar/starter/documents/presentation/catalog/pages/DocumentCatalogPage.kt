package dev.fajar.starter.documents.presentation.catalog.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.documents.domain.entities.DocumentClassification
import dev.fajar.starter.documents.presentation.catalog.*
import dev.fajar.starter.documents.presentation.catalog.widgets.DocumentCatalogEmploymentDialog
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Document
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.Person

@Composable
fun DocumentCatalogPage(
    state: DocumentCatalogState,
    list: LazyListState,
    employments: LazyListState,
    onEvent: (DocumentCatalogEvent) -> Unit,
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
                        { onEvent(DocumentCatalogEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.Folder,
                        appString(AppString.DocumentLocalFiles),
                        { onEvent(DocumentCatalogEvent.FilesRequested) },
                        enabled = state.preparingId == null && !state.choosingEmployment,
                    )
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(DocumentCatalogEvent.RefreshRequested) },
                        enabled = !state.busy,
                        loading = state.loading,
                    )
                },
            )
        },
    ) {
        if (state.loading && state.catalog == null)
            item(key = "loading") { AppLoading(compact = true) }
        val failure = state.restriction ?: state.failure
        if (failure != null && !state.choosingEmployment)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(DocumentCatalogEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(DocumentCatalogEvent.RefreshRequested) },
                            enabled = !state.busy,
                        )
                }
            }
        if (state.directory != null && state.restriction == null) {
            if (state.directory.page.items.isEmpty() && state.employment == null)
                item(key = "no-employment") {
                    AppText(appString(AppString.NoEmployment), color = AppColors.muted)
                }
            else
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (
                            state.directory.page.items.size > 1 ||
                                state.directory.page.nextCursor != null ||
                                state.directory.after != null ||
                                state.employment == null
                        )
                            AppFilterChip(
                                state.employment?.employeeNumber
                                    ?: appString(AppString.ChooseEmployment),
                                selected = state.employment != null,
                                onClick = { onEvent(DocumentCatalogEvent.EmploymentRequested) },
                                enabled = state.canChoose,
                                icon = Icons.Regular.Person,
                            )
                        AppTabs(
                            listOf(
                                AppTabItem(
                                    DocumentClassification.PERSONAL,
                                    appString(AppString.DocumentPersonal),
                                ),
                                AppTabItem(
                                    DocumentClassification.RECEIPT,
                                    appString(AppString.DocumentReceipt),
                                ),
                            ),
                            state.classification,
                            { onEvent(DocumentCatalogEvent.ClassificationChanged(it)) },
                            enabled = state.canChoose,
                        )
                    }
                }
        }
        if (state.expired)
            item(key = "expired") {
                AppText(appString(AppString.DocumentCatalogExpired), color = AppColors.muted)
            }
        if (state.catalog != null) {
            if (state.catalog.page.items.isEmpty())
                item(key = "empty") {
                    AppText(appString(AppString.DocumentNoPublished), color = AppColors.muted)
                }
            items(state.catalog.page.items, key = { it.revisionId }) { document ->
                AppCard(padding = 0.dp) {
                    AppListRow(
                        document.title,
                        { onEvent(DocumentCatalogEvent.DownloadRequested(document.revisionId)) },
                        modifier =
                            Modifier.semantics {
                                if (state.preparingId == document.revisionId)
                                    liveRegion = LiveRegionMode.Polite
                            },
                        supportingText = document.fileName,
                        enabled = state.canChoose && !state.expired,
                        leading = { AppIcon(Icons.Regular.Document) },
                        metadata = {
                            if (state.preparingId == document.revisionId)
                                AppText(
                                    appString(AppString.DocumentPreparingDownload),
                                    style = FluentTheme.typography.caption,
                                    color = AppColors.muted,
                                )
                        },
                        trailing = {
                            if (state.preparingId == document.revisionId)
                                AppProgressRing(appString(AppString.DocumentPreparingDownload))
                            else AppIcon(Icons.Regular.ArrowDownload, appString(AppString.Download))
                        },
                    )
                }
            }
            if (state.catalog.query.after != null || state.catalog.page.nextCursor != null)
                item(key = "paging") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppTextButton(
                            appString(AppString.DirectoryFirstPage),
                            { onEvent(DocumentCatalogEvent.PageRequested(null)) },
                            enabled = state.canChoose && state.catalog.query.after != null,
                        )
                        state.catalog.page.nextCursor?.let { after ->
                            AppTextButton(
                                appString(AppString.DirectoryNextPage),
                                { onEvent(DocumentCatalogEvent.PageRequested(after)) },
                                enabled = state.canChoose,
                            )
                        }
                    }
                }
        }
    }
    if (state.choosingEmployment) DocumentCatalogEmploymentDialog(state, employments, onEvent)
}
