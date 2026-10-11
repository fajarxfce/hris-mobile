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
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Home
import io.github.composefluent.icons.regular.Key
import io.github.composefluent.icons.regular.Person
import io.github.composefluent.icons.regular.Settings

@Composable
fun HomeAccountPage(state: HomeState, onEvent: (HomeEvent) -> Unit) {
    AppPage(maxWidth = 640.dp, contentPadding = PaddingValues(20.dp)) {
        AppScreenHeader(appString(AppString.Account))
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
            if (state.canOpenProfile)
                AppListRow(
                    title = appString(AppString.MyProfile),
                    onClick = { onEvent(HomeEvent.ProfileRequested) },
                    leading = { AppIcon(Icons.Regular.Person) },
                    trailing = { AppIcon(Icons.Regular.ChevronRight) },
                )
            AppListRow(
                title = appString(AppString.Settings),
                onClick = { onEvent(HomeEvent.SettingsRequested) },
                leading = { AppIcon(Icons.Regular.Settings) },
                trailing = { AppIcon(Icons.Regular.ChevronRight) },
            )
            AppListRow(
                title = appString(AppString.ActiveSessions),
                onClick = { onEvent(HomeEvent.SessionsRequested) },
                leading = { AppIcon(Icons.Regular.Key) },
                trailing = { AppIcon(Icons.Regular.ChevronRight) },
            )
        }
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            AppListRow(
                title = state.workspace.company.name,
                supportingText = appString(AppString.ChangeCompany),
                enabled = !state.working,
                onClick = { onEvent(HomeEvent.CompanyChangeRequested) },
                leading = { AppIcon(Icons.Regular.Home) },
                trailing = { AppIcon(Icons.Regular.ChevronRight) },
            )
        }
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
