package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun HomeServicesPage(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    AppPage(
        maxWidth = 640.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(appString(AppString.Services), subtitle = state.workspace.company.name)
        },
    ) {
        state.services.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppHeading(appString(section.title), style = FluentTheme.typography.bodyStrong)
                AppMenuGrid(section.items, itemKey = { it.name }) { service ->
                    AppMenuItem(
                        title = appString(service.title),
                        icon = service.icon,
                        onClick = { onEvent(HomeEvent.ServiceSelected(service)) },
                    )
                }
            }
        }
    }
}
