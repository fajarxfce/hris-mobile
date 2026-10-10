package dev.fajar.starter.auth.presentation.verification.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fajar.starter.auth.presentation.verification.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*

@Composable
fun VerificationPage(state: VerificationState, onEvent: (VerificationEvent) -> Unit) {
    AppPage(maxWidth = 480.dp) {
        AppBrand()
        when (state.step) {
            VerificationStep.Loading,
            VerificationStep.Verified -> {
                AppHeading(appString(AppString.VerifyingAccount))
                if (state.working || state.step == VerificationStep.Verified) AppLoading()
                if (state.failure != null)
                    AppButton(
                        appString(AppString.TryAgain),
                        { onEvent(VerificationEvent.AccountRequested) },
                    )
            }
            VerificationStep.Setup -> {
                AppHeading(appString(AppString.SetUpAuthenticator))
                AppText(appString(AppString.AuthenticatorSetupDescription), color = AppColors.muted)
                if (state.enrollment == null)
                    AppButton(
                        appString(AppString.SetUpAuthenticator),
                        { onEvent(VerificationEvent.EnrollmentRequested) },
                        loading = state.working,
                    )
                else {
                    AppCard(Modifier.fillMaxWidth()) {
                        SelectionContainer { AppText(state.enrollment.secret) }
                    }
                    AppTextField(
                        appString(AppString.VerificationCode),
                        state.code,
                        { onEvent(VerificationEvent.CodeChanged(it)) },
                        enabled = !state.working && !state.uncertain,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = { onEvent(VerificationEvent.EnrollmentConfirmed) }
                            ),
                    )
                    AppButton(
                        appString(AppString.Verify),
                        { onEvent(VerificationEvent.EnrollmentConfirmed) },
                        Modifier.fillMaxWidth(),
                        loading = state.working,
                        enabled = !state.uncertain,
                    )
                }
            }
            VerificationStep.Verify -> {
                AppHeading(appString(AppString.VerifyAccount))
                AppText(
                    appString(
                        if (state.recovery) AppString.RecoveryCodeDescription
                        else AppString.AuthenticatorDescription
                    ),
                    color = AppColors.muted,
                )
                AppTextField(
                    appString(
                        if (state.recovery) AppString.RecoveryCode else AppString.VerificationCode
                    ),
                    state.code,
                    { onEvent(VerificationEvent.CodeChanged(it)) },
                    enabled = !state.working && !state.uncertain,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                if (state.recovery) KeyboardType.Password
                                else KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(onDone = { onEvent(VerificationEvent.CodeSubmitted) }),
                )
                AppButton(
                    appString(AppString.Verify),
                    { onEvent(VerificationEvent.CodeSubmitted) },
                    Modifier.fillMaxWidth(),
                    loading = state.working,
                    enabled = !state.uncertain,
                )
                AppButton(
                    appString(
                        if (state.recovery) AppString.UseAuthenticator
                        else AppString.UseRecoveryCode
                    ),
                    { onEvent(VerificationEvent.RecoveryModeChanged) },
                    primary = false,
                    enabled = !state.working && !state.uncertain,
                )
            }
            VerificationStep.RecoveryCodes -> {
                AppHeading(appString(AppString.RecoveryCodes))
                AppText(appString(AppString.SaveRecoveryCodes), color = AppColors.muted)
                AppCard(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.recoveryCodes.forEach { AppText(it) }
                        }
                    }
                }
                AppButton(
                    appString(AppString.RecoveryCodesSaved),
                    { onEvent(VerificationEvent.RecoveryCodesSaved) },
                    Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.failure != null) AppFeedback(failureText(state.failure))
        if (state.uncertain) AppFeedback(appString(AppString.VerificationUncertain))
        AppButton(
            appString(AppString.SignOut),
            { onEvent(VerificationEvent.SignOutRequested) },
            primary = false,
            enabled = !state.working,
        )
    }
}
