package dev.fajar.starter.home.presentation.services.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.services.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun ServicesPage(state: ServicesState, onEvent: (ServicesEvent) -> Unit) {
    AppPage(
        maxWidth = 640.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                title = appString(AppString.AllServices),
                subtitle = state.companyName,
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ServicesEvent.BackRequested) },
                    )
                },
            )
        },
    ) {
        if (state.loading) AppLoading()
        if (state.failure != null) AppFeedback(failureText(state.failure))
        if (!state.loading && state.failure == null && state.services.sections.isEmpty()) {
            AppText(appString(AppString.ServicesEmpty))
        }
        state.services.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppHeading(appString(section.title), style = FluentTheme.typography.bodyStrong)
                AppMenuGrid(section.items, itemKey = { it.name }) { service ->
                    AppMenuItem(
                        title = appString(service.title),
                        icon = service.icon,
                        onClick = { onEvent(ServicesEvent.ServiceSelected(service)) },
                    )
                }
            }
        }
    }
}
