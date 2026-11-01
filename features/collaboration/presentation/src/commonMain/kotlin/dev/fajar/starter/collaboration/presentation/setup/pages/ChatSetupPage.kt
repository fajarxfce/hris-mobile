package dev.fajar.starter.collaboration.presentation.setup.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.setup.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun ChatSetupPage(
    state: ChatSetupState,
    onEvent: (ChatSetupEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppPage(
        modifier = modifier,
        maxWidth = 600.dp,
        topBar = {
            AppTopBar(
                title = appString(AppString.ChatSetupTitle),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ChatSetupEvent.BackRequested) },
                    )
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppText(
                appString(
                    when (state.phase) {
                        ChatSetupPhase.CHECKING -> AppString.ChatSetupChecking
                        ChatSetupPhase.REGISTER,
                        ChatSetupPhase.BOOTSTRAP -> AppString.ChatSetupActivate
                        ChatSetupPhase.WAITING -> AppString.ChatSetupWaiting
                        ChatSetupPhase.RETRY_PROVISIONING -> AppString.ChatSetupRetry
                        ChatSetupPhase.RESUME -> AppString.ChatSetupResume
                        ChatSetupPhase.READY -> AppString.ChatSetupReady
                        ChatSetupPhase.REVOKED -> AppString.ChatSetupRevoked
                        ChatSetupPhase.UNAVAILABLE -> AppString.ChatSetupUnavailable
                    }
                ),
                style = FluentTheme.typography.subtitle,
            )
            if (state.localFailure == null)
                AppText(
                    appString(
                        when (state.phase) {
                            ChatSetupPhase.REGISTER,
                            ChatSetupPhase.BOOTSTRAP -> AppString.ChatSetupActivateHint
                            ChatSetupPhase.WAITING -> AppString.ChatSetupWaitingHint
                            ChatSetupPhase.RETRY_PROVISIONING -> AppString.ChatSetupRetryHint
                            ChatSetupPhase.RESUME -> AppString.ChatSetupResumeHint
                            ChatSetupPhase.READY -> AppString.ChatSetupReadyHint
                            ChatSetupPhase.REVOKED -> AppString.ChatSetupRevokedHint
                            else -> AppString.ChatSetupCheckHint
                        }
                    ),
                    color = AppColors.muted,
                )
            if (state.loading || state.working) AppLoading()
            val failure = state.localFailure ?: state.failure
            if (failure != null) AppFeedback(failureText(failure))
            val idle = !state.loading && !state.working
            if (state.localFailure == null) {
                when (state.phase) {
                    ChatSetupPhase.REGISTER,
                    ChatSetupPhase.BOOTSTRAP,
                    ChatSetupPhase.RESUME,
                    ChatSetupPhase.RETRY_PROVISIONING ->
                        AppButton(
                            appString(
                                if (
                                    state.phase == ChatSetupPhase.REGISTER ||
                                        state.phase == ChatSetupPhase.BOOTSTRAP
                                )
                                    AppString.ChatSetupEnable
                                else AppString.Continue
                            ),
                            { onEvent(ChatSetupEvent.ContinueRequested) },
                            enabled = idle,
                        )
                    ChatSetupPhase.READY ->
                        AppButton(
                            appString(AppString.ChatSetupOpen),
                            { onEvent(ChatSetupEvent.OpenChatRequested) },
                            enabled = idle && state.readyDeviceId != null,
                        )
                    else -> Unit
                }
            }
            if (state.phase != ChatSetupPhase.READY || failure != null)
                AppTextButton(
                    appString(AppString.Refresh),
                    { onEvent(ChatSetupEvent.RefreshRequested) },
                    enabled = idle,
                )
        }
    }
}
