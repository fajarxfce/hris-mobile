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
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun HomeServicesPage(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    AppPage(maxWidth = 640.dp, contentPadding = PaddingValues(20.dp)) {
        AppScreenHeader(appString(AppString.Services), subtitle = state.workspace.company.name)
        state.services.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppHeading(appString(section.title), style = FluentTheme.typography.bodyStrong)
                AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                    section.items.forEach { service ->
                        AppListRow(
                            title = appString(service.title),
                            supportingText = appString(service.summary),
                            leading = { AppIcon(service.icon) },
                            trailing = { AppIcon(Icons.Regular.ChevronRight) },
                            onClick = { onEvent(HomeEvent.ServiceSelected(service)) },
                        )
                    }
                }
            }
        }
    }
}
