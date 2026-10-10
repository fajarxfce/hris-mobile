package dev.fajar.starter.dashboard.presentation.home.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.dashboard.presentation.home.DashboardState
import dev.fajar.starter.dashboard.presentation.home.widgets.ActivityList
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun ActivityPage(
    state: DashboardState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSavedChanged: (String, Boolean) -> Unit,
) {
    AppPage {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppHeading(appString(AppString.Activity), style = FluentTheme.typography.title)
            AppText(appString(AppString.RecentChanges), color = AppColors.muted)
        }
        AppBadge(appString(AppString.SampleData))
        if (state.error != null) AppFeedback(failureText(state.error))
        if (state.flagError != null) AppFeedback(failureText(state.flagError))
        if (state.loading && state.dashboard == null) AppLoading()
        if (state.dashboard != null) {
            AppText(activityCount(state.dashboard.activity.size))
            if (state.dashboard.pendingChanges > 0) AppBadge(appString(AppString.SyncPending))
            ActivityList(
                state.dashboard.activity,
                if (state.savingAvailable && state.hasSavingPermission) onSavedChanged else null,
            )
        }
        if (state.pageError != null) AppFeedback(failureText(state.pageError))
        if (state.dashboard?.hasMore == true)
            AppButton(
                if (state.pageError == null) appString(AppString.LoadMore)
                else appString(AppString.RetryLoading),
                onLoadMore,
                loading = state.loadingMore,
                enabled = !state.loading,
            )
        AppButton(appString(AppString.Refresh), onRefresh, primary = false, loading = state.loading)
    }
}
