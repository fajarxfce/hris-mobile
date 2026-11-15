package dev.fajar.starter.collaboration.presentation.recipient.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.recipient.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatComposerInput
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Send

@Composable
internal fun RecipientChatComposer(
    state: RecipientChatThreadState,
    onEvent: (RecipientChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Column(modifier.fillMaxWidth().background(AppColors.canvas)) {
        ChatComposerInput(
            text = state.editor.text,
            onTextChanged = { onEvent(RecipientChatThreadEvent.TextChanged(it)) },
            label = appString(AppString.ChatMessage),
            enabled = state.canCompose,
            inputModifier = inputModifier.focusRequester(focus),
            inputStateDescription =
                appString(AppString.ChatDraftSaving).takeIf { state.editor.saving },
        ) {
            AppIconButton(
                Icons.Regular.Send,
                appString(AppString.ChatSend),
                {
                    onEvent(RecipientChatThreadEvent.SendRequested)
                    focus.requestFocus()
                },
                Modifier.background(AppColors.tint, CircleShape),
                enabled = state.canSend,
            )
        }
        if (state.editor.failure != null || state.sourceFailure != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    appString(
                        if (state.sourceFailure != null) AppString.ChatDraftUnavailable
                        else AppString.ChatDraftNotSaved
                    ),
                    Modifier.weight(1f),
                    color = AppColors.muted,
                )
                AppTextButton(
                    appString(AppString.Retry),
                    { onEvent(RecipientChatThreadEvent.RetryRequested) },
                    enabled = !state.leaving && !state.editor.saving && state.restriction == null,
                )
            }
        }
    }
}
