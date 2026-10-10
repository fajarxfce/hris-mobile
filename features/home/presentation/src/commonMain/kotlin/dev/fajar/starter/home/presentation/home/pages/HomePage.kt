package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.Home
import io.github.composefluent.icons.filled.Person
import io.github.composefluent.icons.regular.Home
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
                    HomeTab.Home ->
                        AppPage {
                            AppBrand()
                            AppHeading(state.workspace.company.name)
                            AppText(appString(AppString.CompanyWorkspace), color = AppColors.muted)
                            AppCard(Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AppHeading(state.user?.name.orEmpty())
                                    AppText(state.user?.email.orEmpty(), color = AppColors.muted)
                                }
                            }
                            AppButton(
                                appString(AppString.MyProfile),
                                { onEvent(HomeEvent.ProfileRequested) },
                                Modifier.fillMaxWidth(),
                                primary = false,
                            )
                            AppButton(
                                appString(AppString.ChangeCompany),
                                { onEvent(HomeEvent.CompanyChangeRequested) },
                                primary = false,
                                enabled = !state.working,
                            )
                            if (state.failure != null) AppFeedback(failureText(state.failure))
                        }
                    HomeTab.Account ->
                        AppPage {
                            AppHeading(appString(AppString.Account))
                            AppCard(Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AppHeading(state.user?.name.orEmpty())
                                    AppText(state.user?.email.orEmpty(), color = AppColors.muted)
                                    AppText(state.workspace.company.name)
                                }
                            }
                            AppButton(
                                appString(AppString.MyProfile),
                                { onEvent(HomeEvent.ProfileRequested) },
                                Modifier.fillMaxWidth(),
                                primary = false,
                            )
                            AppButton(
                                appString(AppString.Settings),
                                { onEvent(HomeEvent.SettingsRequested) },
                                Modifier.fillMaxWidth(),
                                primary = false,
                            )
                            AppButton(
                                appString(AppString.ActiveSessions),
                                { onEvent(HomeEvent.SessionsRequested) },
                                Modifier.fillMaxWidth(),
                                primary = false,
                            )
                            AppButton(
                                appString(AppString.ChangeCompany),
                                { onEvent(HomeEvent.CompanyChangeRequested) },
                                Modifier.fillMaxWidth(),
                                primary = false,
                                enabled = !state.working,
                            )
                            AppButton(
                                appString(AppString.SignOut),
                                { onEvent(HomeEvent.SignOutRequested) },
                                Modifier.fillMaxWidth(),
                                primary = false,
                                loading = state.working,
                            )
                            if (state.failure != null) AppFeedback(failureText(state.failure))
                        }
                }
            }
        }
    }
}
