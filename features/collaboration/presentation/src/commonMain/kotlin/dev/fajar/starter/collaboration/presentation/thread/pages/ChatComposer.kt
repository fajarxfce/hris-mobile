package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Checkmark
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.Edit
import io.github.composefluent.icons.regular.Send

@Composable
internal fun ChatComposer(
    state: ChatThreadState,
    onEvent: (ChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
) {
    val inputFocus = remember { FocusRequester() }
    val editing = state.editing
    val text = editing?.text ?: state.draft
    val label =
        appString(if (editing != null) AppString.ChatEditingMessage else AppString.ChatMessage)
    val savingLabel = appString(AppString.ChatDraftSaving)
    Column(modifier.fillMaxWidth().background(AppColors.canvas)) {
        if (editing != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(Icons.Regular.Edit, null, Modifier.size(20.dp), tint = AppColors.accent)
                AppText(
                    appString(AppString.ChatEditingMessage),
                    Modifier.weight(1f),
                    color = AppColors.accent,
                )
                AppIconButton(
                    Icons.Regular.Dismiss,
                    appString(AppString.ChatCancelEdit),
                    { onEvent(ChatThreadEvent.EditCancelled) },
                )
            }
        }
        state.draftReplyToEventId
            ?.takeIf { editing == null }
            ?.let { target ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatQuotePreview(
                        state.quote(target),
                        state.selfSender,
                        state.title,
                        Modifier.weight(1f),
                    )
                    AppIconButton(
                        Icons.Regular.Dismiss,
                        appString(AppString.ChatRemoveReply),
                        { onEvent(ChatThreadEvent.ReplyCleared) },
                        enabled = state.canCompose,
                    )
                }
            }
        ChatComposerInput(
            text = text,
            onTextChanged = {
                onEvent(
                    if (editing == null) ChatThreadEvent.DraftChanged(it)
                    else ChatThreadEvent.EditChanged(it)
                )
            },
            label = label,
            enabled = if (editing == null) state.canCompose else state.canEditInput,
            inputModifier = inputModifier.focusRequester(inputFocus),
            readOnly = editing?.let { it.busy || it.locked } == true,
            inputStateDescription = savingLabel.takeIf { editing == null && state.draftSaving },
        ) {
            AppIconButton(
                if (editing == null) Icons.Regular.Send else Icons.Regular.Checkmark,
                appString(
                    if (editing != null) AppString.ChatSaveEdit
                    else if (state.submissionPending) AppString.Retry else AppString.ChatSend
                ),
                {
                    onEvent(
                        if (editing == null) ChatThreadEvent.SendRequested
                        else ChatThreadEvent.EditSaveRequested
                    )
                    if (editing == null) inputFocus.requestFocus()
                },
                modifier = Modifier.background(AppColors.tint, CircleShape),
                enabled =
                    if (editing == null) state.canSend else state.canEditInput && editing.canSave,
            )
        }

        (editing?.failure ?: state.editFailure)?.let { failure ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    appString(
                        if (failure.code == "client.collaboration_edit_unavailable")
                            AppString.ChatEditUnavailable
                        else AppString.ChatEditNotSaved
                    ),
                    Modifier.weight(1f),
                    color = AppColors.muted,
                )
                if (editing == null)
                    AppIconButton(
                        Icons.Regular.Dismiss,
                        appString(AppString.Close),
                        { onEvent(ChatThreadEvent.EditFailureDismissed) },
                    )
            }
        }
        state.draftFailure
            ?.takeIf { editing == null }
            ?.let { failure ->
                val changed = failure.code == "client.collaboration_draft_changed"
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        appString(
                            when {
                                changed -> AppString.ChatDraftChanged
                                failure.code == "client.collaboration_invalid_message" ->
                                    AppString.ChatDraftInvalid
                                else -> AppString.ChatDraftNotSaved
                            }
                        ),
                        modifier = Modifier.weight(1f),
                        color = AppColors.muted,
                    )
                    if (!state.submissionPending || changed)
                        AppTextButton(
                            appString(if (changed) AppString.ChatDraftReload else AppString.Retry),
                            { onEvent(ChatThreadEvent.DraftRetryRequested) },
                            enabled = !state.saving && !state.draftSaving && !state.leaving,
                        )
                }
            }
    }
}
