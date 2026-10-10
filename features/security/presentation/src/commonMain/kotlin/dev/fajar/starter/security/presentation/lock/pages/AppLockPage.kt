package dev.fajar.starter.security.presentation.lock.pages

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.security.presentation.lock.*
import io.github.composefluent.FluentTheme

@Composable
fun AppLockPage(state: AppLockState, onEvent: (AppLockEvent) -> Unit) {
    AppPage {
        AppBrand()
        AppHeading(appString(AppString.AppLocked), style = FluentTheme.typography.title)
        AppText(appString(AppString.AppLockedDescription))
        if (state.failure != null) AppFeedback(appString(AppString.DeviceAuthenticationFailed))
        AppButton(
            appString(AppString.UnlockApp),
            { onEvent(AppLockEvent.UnlockRequested) },
            enabled = !state.authenticating && !state.resetting,
        )
        AppButton(
            appString(AppString.UseAccountSignIn),
            { onEvent(AppLockEvent.AccountSignInRequested) },
            primary = false,
            enabled = !state.resetting,
        )
    }
}
