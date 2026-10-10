package dev.fajar.starter.dashboard.presentation.home.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.dashboard.presentation.home.DashboardState
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun AccountPage(state: DashboardState, onSignOut: () -> Unit) {
    AppPage(maxWidth = 600.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("Account", style = FluentTheme.typography.title)
            AppText("Your profile and session.", color = AppColors.muted)
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppBadge("Demo account")
                AppText(state.user?.name.orEmpty(), style = FluentTheme.typography.subtitle)
                AppText(state.user?.email.orEmpty(), color = AppColors.muted)
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            AppText("Workspace", style = FluentTheme.typography.bodyStrong)
            Spacer(Modifier.height(8.dp))
            AppText("Personal workspace", color = AppColors.muted)
        }
        if (state.error != null) AppFeedback(state.error)
        AppButton(
            "Sign out",
            onSignOut,
            Modifier.fillMaxWidth(),
            primary = false,
            loading = state.signingOut,
        )
    }
}
