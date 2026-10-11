package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronDown
import io.github.composefluent.icons.regular.Home

@Composable
fun HomeOverview(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    AppPage(maxWidth = 640.dp, contentPadding = PaddingValues(20.dp)) {
        AppScreenHeader(
            title = state.user?.name ?: appString(AppString.Home),
            subtitle = appString(AppString.CompanyWorkspace),
        )
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            AppListRow(
                title = state.workspace.company.name,
                supportingText = appString(AppString.ChangeCompany),
                emphasized = true,
                enabled = !state.working,
                leading = { AppIcon(Icons.Regular.Home) },
                trailing = { AppIcon(Icons.Regular.ChevronDown) },
                onClick = { onEvent(HomeEvent.CompanyChangeRequested) },
            )
        }
        if (state.services.shortcuts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppHeading(appString(AppString.MyWork), style = FluentTheme.typography.bodyStrong)
                state.services.shortcuts.forEach { service ->
                    AppActionTile(
                        title = appString(service.title),
                        supportingText = appString(service.summary),
                        icon = service.icon,
                        onClick = { onEvent(HomeEvent.ServiceSelected(service)) },
                    )
                }
                AppButton(
                    appString(AppString.AllServices),
                    { onEvent(HomeEvent.TabSelected(HomeTab.Services)) },
                    primary = false,
                )
            }
        }
        if (state.failure != null) AppFeedback(failureText(state.failure))
    }
}
