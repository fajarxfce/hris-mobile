package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.Chat
import io.github.composefluent.icons.filled.Home
import io.github.composefluent.icons.filled.Mail
import io.github.composefluent.icons.filled.Person
import io.github.composefluent.icons.regular.Chat
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Mail
import io.github.composefluent.icons.regular.Person

@Composable
fun HomePage(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    attendance: @Composable () -> Unit,
    inbox: @Composable () -> Unit,
    employees: @Composable (Boolean, Dp) -> Unit,
    inboxBadge: @Composable () -> Unit,
    chat: @Composable (Boolean, Dp) -> Unit,
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
                        HomeTab.Chat,
                        appString(AppString.ChatTitle),
                        Icons.Regular.Chat,
                        Icons.Filled.Chat,
                    ),
                    AppNavigationItem(
                        HomeTab.Inbox,
                        appString(AppString.Inbox),
                        Icons.Regular.Mail,
                        Icons.Filled.Mail,
                        badge = inboxBadge,
                    ),
                    AppNavigationItem(
                        HomeTab.Account,
                        appString(AppString.Account),
                        Icons.Regular.Person,
                        Icons.Filled.Person,
                    ),
                ),
            selected = if (state.tab == HomeTab.Employees) HomeTab.Chat else state.tab,
            onSelected = { onEvent(HomeEvent.TabSelected(it)) },
        ) {
            AppTabContent(state.tab, stateKey = { it.name }) { tab ->
                when (tab) {
                    HomeTab.Home -> HomeOverview(state, onEvent, attendance)
                    HomeTab.Chat,
                    HomeTab.Employees -> HomeChatPage(state, onEvent, chat, employees)
                    HomeTab.Inbox -> inbox()
                    HomeTab.Account -> HomeAccountPage(state, onEvent)
                }
            }
        }
    }
}
