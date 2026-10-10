package dev.fajar.starter.dashboard.presentation.home.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.dashboard.presentation.home.DashboardState
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun AccountPage(
    state: DashboardState,
    onSignOut: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    onFiles: () -> Unit,
) {
    AppPage(maxWidth = 600.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppHeading(appString(AppString.Account), style = FluentTheme.typography.title)
            AppText(appString(AppString.ProfileDescription), color = AppColors.muted)
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppBadge(appString(AppString.DemoAccount))
                AppText(state.user?.name.orEmpty(), style = FluentTheme.typography.subtitle)
                AppText(state.user?.email.orEmpty(), color = AppColors.muted)
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            AppText(appString(AppString.Workspace), style = FluentTheme.typography.bodyStrong)
            Spacer(Modifier.height(8.dp))
            AppText(appString(AppString.PersonalWorkspace), color = AppColors.muted)
        }
        AppButton(
            appString(AppString.Notifications),
            onNotifications,
            Modifier.fillMaxWidth(),
            primary = false,
        )
        AppButton(
            appString(AppString.Settings),
            onSettings,
            Modifier.fillMaxWidth(),
            primary = false,
        )
        AppButton(appString(AppString.Files), onFiles, Modifier.fillMaxWidth(), primary = false)
        if (state.error != null) AppFeedback(failureText(state.error))
        AppButton(
            appString(AppString.SignOut),
            onSignOut,
            Modifier.fillMaxWidth(),
            primary = false,
            loading = state.signingOut,
        )
    }
}
