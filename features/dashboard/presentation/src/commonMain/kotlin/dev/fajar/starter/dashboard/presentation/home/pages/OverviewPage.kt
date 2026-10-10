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
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun OverviewPage(state: DashboardState, onRefresh: () -> Unit, onActivity: () -> Unit) {
    AppPage {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppHeading(appString(AppString.Overview), style = FluentTheme.typography.title)
            AppText(appString(AppString.WorkspaceSummary), color = AppColors.muted)
        }
        AppCard(Modifier.fillMaxWidth(), color = AppColors.tint) {
            AppText(appString(AppString.PersonalWorkspace), style = FluentTheme.typography.subtitle)
            Spacer(Modifier.height(6.dp))
            AppText(
                appString(AppString.Welcome, state.user?.name.orEmpty()),
                color = AppColors.muted,
            )
        }
        if (state.error != null) AppFeedback(failureText(state.error))
        if (state.loading && state.dashboard == null) AppLoading()
        if (state.dashboard != null) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 3,
            ) {
                SummaryCard(
                    appString(AppString.Projects),
                    formatNumber(state.dashboard.projects.toLong(), appLanguageTag()),
                    Modifier.widthIn(min = 160.dp).weight(1f),
                )
                SummaryCard(
                    appString(AppString.Active),
                    formatNumber(state.dashboard.active.toLong(), appLanguageTag()),
                    Modifier.widthIn(min = 160.dp).weight(1f),
                )
                SummaryCard(
                    appString(AppString.Members),
                    formatNumber(state.dashboard.members.toLong(), appLanguageTag()),
                    Modifier.widthIn(min = 160.dp).weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppText(
                    appString(AppString.RecentActivity),
                    style = FluentTheme.typography.subtitle,
                )
                AppBadge(appString(AppString.SampleData))
            }
            ActivityList(state.dashboard.activity.take(3))
            AppText(
                appString(
                    AppString.UpdatedAt,
                    formatDate(state.dashboard.updatedAtEpochMillis, appLanguageTag()),
                )
            )
            AppButton(
                appString(AppString.ViewActivity),
                onActivity,
                Modifier.fillMaxWidth(),
                primary = false,
            )
        }
        AppButton(appString(AppString.Refresh), onRefresh, primary = false, loading = state.loading)
    }
}
