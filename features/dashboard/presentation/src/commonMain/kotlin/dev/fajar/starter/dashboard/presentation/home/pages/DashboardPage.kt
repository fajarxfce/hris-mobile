package dev.fajar.starter.dashboard.presentation.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.dashboard.presentation.home.DashboardEvent
import dev.fajar.starter.dashboard.presentation.home.DashboardState
import dev.fajar.starter.dashboard.presentation.home.DashboardTab
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.History
import io.github.composefluent.icons.filled.Home
import io.github.composefluent.icons.filled.Person
import io.github.composefluent.icons.regular.History
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Person

@Composable
fun DashboardPage(state: DashboardState, onEvent: (DashboardEvent) -> Unit) {
    Column(Modifier.fillMaxSize().background(AppColors.canvas)) {
        Row(
            Modifier.fillMaxWidth()
                .background(AppColors.surface)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppBrand()
            AppBadge(appString(AppString.Demo))
        }
        AppNavigationScaffold(
            modifier = Modifier.weight(1f),
            items =
                listOf(
                    AppNavigationItem(
                        DashboardTab.Overview,
                        appString(AppString.Overview),
                        Icons.Regular.Home,
                        Icons.Filled.Home,
                    ),
                    AppNavigationItem(
                        DashboardTab.Activity,
                        appString(AppString.Activity),
                        Icons.Regular.History,
                        Icons.Filled.History,
                    ),
                    AppNavigationItem(
                        DashboardTab.Account,
                        appString(AppString.Account),
                        Icons.Regular.Person,
                        Icons.Filled.Person,
                    ),
                ),
            selected = state.tab,
            onSelected = { onEvent(DashboardEvent.TabSelected(it)) },
        ) {
            when (state.tab) {
                DashboardTab.Overview ->
                    OverviewPage(
                        state,
                        { onEvent(DashboardEvent.RefreshRequested) },
                        { onEvent(DashboardEvent.TabSelected(DashboardTab.Activity)) },
                    )
                DashboardTab.Activity ->
                    ActivityPage(
                        state = state,
                        onRefresh = { onEvent(DashboardEvent.RefreshRequested) },
                        onLoadMore = { onEvent(DashboardEvent.NextPageRequested) },
                        onSavedChanged = { id, saved ->
                            onEvent(DashboardEvent.ActivitySavedChanged(id, saved))
                        },
                    )
                DashboardTab.Account ->
                    AccountPage(
                        state,
                        { onEvent(DashboardEvent.SignOutRequested) },
                        { onEvent(DashboardEvent.NotificationsRequested) },
                        { onEvent(DashboardEvent.SettingsRequested) },
                        { onEvent(DashboardEvent.FilesRequested) },
                    )
            }
        }
    }
}
