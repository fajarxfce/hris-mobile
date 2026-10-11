package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronDown

@Composable
fun HomeOverview(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    attendance: @Composable () -> Unit,
) {
    AppPage(
        maxWidth = 640.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        spacing = 16.dp,
        topBar = {
            AppTopBar(
                title = state.workspace.company.name,
                subtitle = appString(AppString.CompanyWorkspace),
                actions = {
                    AppIconButton(
                        Icons.Regular.ChevronDown,
                        appString(AppString.ChangeCompany),
                        { onEvent(HomeEvent.CompanyChangeRequested) },
                        enabled = !state.working,
                    )
                },
            )
        },
    ) {
        AppHeading(state.user?.name.orEmpty(), style = FluentTheme.typography.subtitle)
        if (HomeService.Attendance in state.services.shortcuts) attendance()
        if (state.services.shortcuts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppHeading(
                        appString(AppString.MyWork),
                        Modifier.weight(1f),
                        style = FluentTheme.typography.bodyStrong,
                    )
                    AppTextButton(
                        appString(AppString.AllServices),
                        { onEvent(HomeEvent.TabSelected(HomeTab.Services)) },
                    )
                }
                AppMenuGrid(state.services.shortcuts, itemKey = { it.name }) { service ->
                    AppMenuItem(
                        title = appString(service.title),
                        icon = service.icon,
                        onClick = { onEvent(HomeEvent.ServiceSelected(service)) },
                    )
                }
            }
        }
        if (state.failure != null) AppFeedback(failureText(state.failure))
    }
}
