package dev.fajar.starter.dashboard.presentation.home.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.dashboard.presentation.home.DashboardState
import dev.fajar.starter.dashboard.presentation.home.widgets.ActivityList
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun ActivityPage(state: DashboardState, onRefresh: () -> Unit) {
    AppPage {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("Activity", style = FluentTheme.typography.title)
            AppText("Recent changes in your workspace.", color = AppColors.muted)
        }
        AppBadge("Sample data")
        if (state.error != null) AppFeedback(state.error)
        if (state.loading && state.dashboard == null) AppLoading()
        if (state.dashboard != null) ActivityList(state.dashboard.activity)
        AppButton("Refresh", onRefresh, primary = false, loading = state.loading)
    }
}
