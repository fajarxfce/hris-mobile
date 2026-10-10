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
import io.github.composefluent.FluentTheme

@Composable
fun LoginPage(state: LoginState, onEvent: (LoginEvent) -> Unit) {
    AppPage(maxWidth = 480.dp) {
        AppBrand()
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("Sign in", style = FluentTheme.typography.titleLarge)
            AppText("Use your account to continue.", color = AppColors.muted)
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AppTextField(
                    "Email",
                    state.email,
                    { onEvent(LoginEvent.EmailChanged(it)) },
                    placeholder = "name@example.com",
                    enabled = !state.submitting,
                    error = state.failure?.takeIf { it.field == "email" }?.message,
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
                    error = state.failure?.takeIf { it.field == "password" }?.message,
                    keyboardActions =
                        KeyboardActions(onDone = { onEvent(LoginEvent.SignInRequested) }),
                )
                if (state.failure != null && state.failure.field == null)
                    AppFeedback(state.failure.message)
                AppButton(
                    "Sign in",
                    { onEvent(LoginEvent.SignInRequested) },
                    Modifier.fillMaxWidth(),
                    loading = state.submitting,
                )
            }
        }
        AppCard(Modifier.fillMaxWidth(), color = AppColors.tint) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppBadge("Demo")
                AppText("Explore with a sample account.", style = FluentTheme.typography.bodyStrong)
                AppText(
                    "demo@example.com  /  Demo123!",
                    style = FluentTheme.typography.caption,
                    color = AppColors.muted,
                )
                AppButton(
                    "Use demo account",
                    { onEvent(LoginEvent.DemoAccountSelected) },
                    Modifier.fillMaxWidth(),
                    primary = false,
                    enabled = !state.submitting,
                )
            }
        }
    }
}
