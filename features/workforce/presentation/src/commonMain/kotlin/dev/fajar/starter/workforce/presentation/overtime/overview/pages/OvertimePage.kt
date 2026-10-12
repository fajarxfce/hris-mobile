package dev.fajar.starter.workforce.presentation.overtime.overview.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.overview.*
import dev.fajar.starter.workforce.presentation.overtime.overview.widgets.*
import dev.fajar.starter.workforce.presentation.overtime.widgets.OvertimeReadFeedback
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun OvertimePage(state: OvertimeState, list: LazyListState, onEvent: (OvertimeEvent) -> Unit) {
    AppLazyPage(
        state = list,
        spacing = 12.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.Overtime),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(OvertimeEvent.BackRequested) },
                        )
                    },
                    actions = {
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(OvertimeEvent.RefreshRequested) },
                            enabled = !state.refreshing,
                            loading = state.refreshing,
                        )
                    },
                )
                OvertimeFilters(state, onEvent)
                state.failure?.let { failure ->
                    OvertimeReadFeedback(
                        failure,
                        state.needsVerification,
                        state.refreshing,
                        { onEvent(OvertimeEvent.VerificationRequested) },
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (state.hasPlanningAccess)
                AppActionBar {
                    AppButton(
                        appString(AppString.OvertimeNewPlan),
                        { onEvent(OvertimeEvent.PlanRequested) },
                        Modifier.fillMaxWidth(),
                        enabled = state.canPlan,
                    )
                }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = state.content != null) }
        val content = state.content?.takeUnless { state.readRestricted }?.value
        if (content?.directory?.items?.isEmpty() == true)
            item(key = "no-employment") { AppText(appString(AppString.CalendarNoEmployment)) }
        if (content?.selection?.employeeId != null && content.requests.items.isEmpty())
            item(key = "empty-requests") {
                AppText(appString(AppString.OvertimeNoRequests), color = AppColors.muted)
            }
        items(content?.requests?.items.orEmpty(), key = { "request/${it.id}" }) { request ->
            OvertimeRequestRow(request) { onEvent(OvertimeEvent.RequestSelected(request.id)) }
        }
        if (
            content != null &&
                (content.selection.requestAfter != null || content.requests.nextCursor != null)
        )
            item(key = "pages") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (content.selection.requestAfter != null)
                        AppTextButton(
                            appString(AppString.OvertimeFirstRequests),
                            { onEvent(OvertimeEvent.RequestsPageRequested(null)) },
                            enabled = !state.refreshing,
                        )
                    if (content.requests.nextCursor != null)
                        AppTextButton(
                            appString(AppString.OvertimeMoreRequests),
                            {
                                onEvent(
                                    OvertimeEvent.RequestsPageRequested(content.requests.nextCursor)
                                )
                            },
                            enabled = !state.refreshing,
                        )
                }
            }
        if (content != null)
            item(key = "saved") {
                AppText(
                    appString(
                        AppString.OvertimeSaved,
                        formatDate(state.content.fetchedAt.toEpochMilliseconds(), appLanguageTag()),
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        else if (!state.loading && !state.refreshing && state.failure == null)
            item(key = "empty") { AppText(appString(AppString.OvertimeNoSaved)) }
    }
    if (state.dialog != null && !state.readRestricted) OvertimeFilterDialog(state, onEvent)
}
