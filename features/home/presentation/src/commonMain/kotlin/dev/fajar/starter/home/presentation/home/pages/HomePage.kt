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
import io.github.composefluent.icons.filled.Mail
import io.github.composefluent.icons.filled.People
import io.github.composefluent.icons.filled.Person
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Mail
import io.github.composefluent.icons.regular.People
import io.github.composefluent.icons.regular.Person

@Composable
fun HomePage(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    attendance: @Composable () -> Unit,
    inbox: @Composable () -> Unit,
    employees: @Composable () -> Unit,
    inboxBadge: @Composable () -> Unit,
) {
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
                        HomeTab.Inbox,
                        appString(AppString.Inbox),
                        Icons.Regular.Mail,
                        Icons.Filled.Mail,
                        badge = inboxBadge,
                    ),
                    AppNavigationItem(
                        HomeTab.Employees,
                        appString(AppString.Employees),
                        Icons.Regular.People,
                        Icons.Filled.People,
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
                    HomeTab.Home -> HomeOverview(state, onEvent, attendance)
                    HomeTab.Inbox -> inbox()
                    HomeTab.Employees -> employees()
                    HomeTab.Account -> HomeAccountPage(state, onEvent)
                }
            }
        }
    }
}
