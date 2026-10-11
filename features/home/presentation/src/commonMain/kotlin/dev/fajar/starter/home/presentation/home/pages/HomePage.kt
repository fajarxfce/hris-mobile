package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.Home
import io.github.composefluent.icons.filled.Navigation
import io.github.composefluent.icons.filled.Person
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Navigation
import io.github.composefluent.icons.regular.Person

@Composable
fun HomePage(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    Column(Modifier.fillMaxSize().background(AppColors.canvas)) {
        AppNavigationScaffold(
            items =
                listOf(
                    AppNavigationItem(
                        HomeTab.Home,
                        appString(AppString.Home),
                        Icons.Regular.Home,
                        Icons.Filled.Home,
                    ),
                    AppNavigationItem(
                        HomeTab.Services,
                        appString(AppString.Services),
                        Icons.Regular.Navigation,
                        Icons.Filled.Navigation,
                    ),
                    AppNavigationItem(
                        HomeTab.Account,
                        appString(AppString.Account),
                        Icons.Regular.Person,
                        Icons.Filled.Person,
                    ),
                ),
            selected = state.tab,
            onSelected = { onEvent(HomeEvent.TabSelected(it)) },
        ) {
            AppTabContent(state.tab, stateKey = { it.name }) { tab ->
                when (tab) {
                    HomeTab.Home -> HomeOverview(state, onEvent)
                    HomeTab.Services -> HomeServicesPage(state, onEvent)
                    HomeTab.Account -> HomeAccountPage(state, onEvent)
                }
            }
        }
    }
}
