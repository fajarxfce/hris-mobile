package dev.fajar.starter.dashboard.presentation.home.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.dashboard.presentation.home.DashboardState
import dev.fajar.starter.dashboard.presentation.home.widgets.ActivityList
import dev.fajar.starter.dashboard.presentation.home.widgets.SummaryCard
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun OverviewPage(state: DashboardState, onRefresh: () -> Unit, onActivity: () -> Unit) {
    AppPage {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("Overview", style = FluentTheme.typography.title)
            AppText("Your workspace at a glance.", color = AppColors.muted)
        }
        AppCard(Modifier.fillMaxWidth(), color = AppColors.tint) {
            AppText("Personal workspace", style = FluentTheme.typography.subtitle)
            Spacer(Modifier.height(6.dp))
            AppText("Welcome, ${state.user?.name.orEmpty()}.", color = AppColors.muted)
        }
        if (state.error != null) AppFeedback(state.error)
        if (state.loading && state.dashboard == null) AppLoading()
        if (state.dashboard != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("Projects", state.dashboard.projects.toString(), Modifier.weight(1f))
                SummaryCard("Active", state.dashboard.active.toString(), Modifier.weight(1f))
                SummaryCard("Members", state.dashboard.members.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppText("Recent activity", style = FluentTheme.typography.subtitle)
                AppBadge("Sample data")
            }
            ActivityList(state.dashboard.activity.take(3))
            AppButton("View all activity", onActivity, Modifier.fillMaxWidth(), primary = false)
        }
        AppButton("Refresh", onRefresh, primary = false, loading = state.loading)
    }
}
