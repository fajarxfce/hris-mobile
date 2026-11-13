package dev.fajar.starter.collaboration.presentation.spacethread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatComposerInput
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.Send

@Composable
internal fun SpaceChatComposer(
    state: SpaceChatThreadState,
    onEvent: (SpaceChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Column(modifier.fillMaxWidth().background(AppColors.canvas)) {
        state.replyToEventId?.let { target ->
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpaceChatQuotePreview(state, target, Modifier.weight(1f))
                AppIconButton(
                    Icons.Regular.Dismiss,
                    appString(AppString.ChatRemoveReply),
                    { onEvent(SpaceChatThreadEvent.ReplyCleared) },
                    enabled = state.canCompose,
                )
            }
        }
        ChatComposerInput(
            text = state.draft,
            onTextChanged = { onEvent(SpaceChatThreadEvent.DraftChanged(it)) },
            label = appString(AppString.ChatMessage),
            enabled = state.canCompose,
            inputModifier = inputModifier.focusRequester(focus),
            inputStateDescription =
                appString(AppString.ChatDraftSaving).takeIf { state.draftSaving },
        ) {
            AppIconButton(
                Icons.Regular.Send,
                appString(AppString.ChatSend),
                {
                    onEvent(SpaceChatThreadEvent.SendRequested)
                    focus.requestFocus()
                },
                Modifier.background(AppColors.tint, CircleShape),
                enabled = state.canSend,
            )
        }
        if (state.draftFailure != null || state.composerReadFailure != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    appString(
                        if (state.composerReadFailure != null) AppString.ChatDraftUnavailable
                        else AppString.ChatDraftNotSaved
                    ),
                    Modifier.weight(1f),
                    color = AppColors.muted,
                )
                AppTextButton(
                    appString(AppString.Retry),
                    { onEvent(SpaceChatThreadEvent.DraftRetryRequested) },
                    enabled = !state.leaving && !state.draftSaving && state.restriction == null,
                )
            }
        }
    }
}
