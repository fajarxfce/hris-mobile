package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
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
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    onEvent(
                        if (editing == null) ChatThreadEvent.DraftChanged(it)
                        else ChatThreadEvent.EditChanged(it)
                    )
                },
                modifier =
                    inputModifier.weight(1f).heightIn(min = 48.dp).semantics {
                        contentDescription = label
                        if (editing == null && state.draftSaving) stateDescription = savingLabel
                    },
                enabled = if (editing == null) state.canCompose else state.canEditInput,
                readOnly = editing?.let { it.busy || it.locked } == true,
                singleLine = false,
                maxLines = 6,
                textStyle = FluentTheme.typography.body.copy(color = AppColors.text),
                cursorBrush = SolidColor(AppColors.accent),
                decorationBox = { input ->
                    Box(
                        Modifier.background(AppColors.surface, RoundedCornerShape(24.dp))
                            .border(1.dp, AppColors.border, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) AppText(label, color = AppColors.muted)
                        input()
                    }
                },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
            )
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
