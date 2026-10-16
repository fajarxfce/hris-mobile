package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.home.presentation.catalog.HomeService
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowRight
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.ChevronDown
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Key
import io.github.composefluent.icons.regular.Person
import io.github.composefluent.icons.regular.Settings

@Composable
fun HomeAccountPage(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    AppPage(
        maxWidth = 640.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.Account),
                subtitle =
                    if (state.operation == HomeOperation.ChangingCompany)
                        appString(AppString.ChangingCompany)
                    else state.workspace.company.name,
                actions = {
                    AppIconButton(
                        Icons.Regular.ChevronDown,
                        appString(AppString.ChangeCompany),
                        { onEvent(HomeEvent.CompanyChangeRequested) },
                        enabled = !state.working,
                        loading = state.operation == HomeOperation.ChangingCompany,
                    )
                },
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(56.dp).background(AppColors.tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Icons.Regular.Person, tint = AppColors.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppHeading(state.user?.name.orEmpty(), style = FluentTheme.typography.subtitle)
                AppText(state.user?.email.orEmpty(), color = AppColors.muted)
            }
        }
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            if (HomeService.Profile in state.services.account)
                AppListRow(
                    title = appString(AppString.MyProfile),
                    onClick = { onEvent(HomeEvent.ServiceSelected(HomeService.Profile)) },
                    enabled = !state.working,
                    leading = { AppIcon(Icons.Regular.Person) },
                    trailing = { AppIcon(Icons.Regular.ChevronRight) },
                )
            AppListRow(
                title = appString(AppString.Settings),
                onClick = { onEvent(HomeEvent.SettingsRequested) },
                enabled = !state.working,
                leading = { AppIcon(Icons.Regular.Settings) },
                trailing = { AppIcon(Icons.Regular.ChevronRight) },
            )
            AppListRow(
                title = appString(AppString.ActiveSessions),
                onClick = { onEvent(HomeEvent.SessionsRequested) },
                enabled = !state.working,
                leading = { AppIcon(Icons.Regular.Key) },
                trailing = { AppIcon(Icons.Regular.ChevronRight) },
            )
            AppListRow(
                title = appString(AppString.DataAndSync),
                onClick = { onEvent(HomeEvent.DataSyncRequested) },
                enabled = !state.working,
                leading = { AppIcon(Icons.Regular.ArrowSync) },
                trailing = { AppIcon(Icons.Regular.ChevronRight) },
            )
        }
        AppListRow(
            title =
                appString(
                    if (state.operation == HomeOperation.SigningOut) AppString.SigningOut
                    else AppString.SignOut
                ),
            onClick = { onEvent(HomeEvent.SignOutRequested) },
            leading = { AppIcon(Icons.Regular.ArrowRight) },
            enabled = !state.working,
            trailing = {
                if (state.operation == HomeOperation.SigningOut)
                    AppProgressRing(appString(AppString.SigningOut))
            },
        )
        if (state.failure != null) AppFeedback(failureText(state.failure))
    }
}
