package dev.fajar.starter.auth.presentation.login.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fajar.starter.auth.presentation.login.LoginEvent
import dev.fajar.starter.auth.presentation.login.LoginState
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LoginPage(state: LoginState, onEvent: (LoginEvent) -> Unit) {
    AppPage(maxWidth = 480.dp) {
        AppBrand()
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppHeading(appString(AppString.SignIn), style = FluentTheme.typography.titleLarge)
            AppText(appString(AppString.SignInDescription), color = AppColors.muted)
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AppTextField(
                    appString(AppString.Email),
                    state.email,
                    { onEvent(LoginEvent.EmailChanged(it)) },
                    placeholder = "name@example.com",
                    enabled = !state.submitting,
                    error = fieldErrorText(state.fieldErrors["email"]),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                )
                AppPasswordField(
                    value = state.password,
                    onValueChange = { onEvent(LoginEvent.PasswordChanged(it)) },
                    visible = state.passwordVisible,
                    onVisibilityChanged = { onEvent(LoginEvent.PasswordVisibilityChanged) },
                    enabled = !state.submitting,
                    error = fieldErrorText(state.fieldErrors["password"]),
                    keyboardActions =
                        KeyboardActions(onDone = { onEvent(LoginEvent.SignInRequested) }),
                )
                if (state.failure != null && state.fieldErrors.isEmpty())
                    AppFeedback(failureText(state.failure))
                AppButton(
                    appString(AppString.SignIn),
                    { onEvent(LoginEvent.SignInRequested) },
                    Modifier.fillMaxWidth(),
                    loading = state.submitting && state.pendingProviderId == null,
                    enabled = !state.submitting,
                )
            }
        }
        state.providers.forEach { provider ->
            AppButton(
                appString(AppString.SignInWithProvider, provider.label),
                { onEvent(LoginEvent.SsoRequested(provider.id)) },
                Modifier.fillMaxWidth(),
                primary = false,
                enabled = !state.submitting,
                loading = state.submitting && state.pendingProviderId == provider.id,
            )
        }
        if (state.pendingProviderId != null)
            AppButton(
                appString(AppString.Cancel),
                { onEvent(LoginEvent.SsoCancellationRequested) },
                primary = false,
            )
        AppCard(Modifier.fillMaxWidth(), color = AppColors.tint) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppBadge(appString(AppString.Demo))
                AppText(
                    appString(AppString.DemoDescription),
                    style = FluentTheme.typography.bodyStrong,
                )
                AppText(
                    "demo@example.com  /  Demo123!",
                    style = FluentTheme.typography.caption,
                    color = AppColors.muted,
                )
                AppButton(
                    appString(AppString.UseDemo),
                    { onEvent(LoginEvent.DemoAccountSelected) },
                    Modifier.fillMaxWidth(),
                    primary = false,
                    enabled = !state.submitting,
                )
            }
        }
    }
}
