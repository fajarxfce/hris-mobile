package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Send

@Composable
internal fun ChatComposer(
    state: ChatThreadState,
    onEvent: (ChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = appString(AppString.ChatMessage)
    val savingLabel = appString(AppString.ChatDraftSaving)
    Column(modifier.fillMaxWidth().background(AppColors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextField(
                value = state.draft,
                onValueChange = { onEvent(ChatThreadEvent.DraftChanged(it)) },
                modifier =
                    Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp).semantics {
                        contentDescription = label
                        if (state.draftSaving) stateDescription = savingLabel
                    },
                enabled = state.canCompose,
                singleLine = false,
                isClearable = false,
                shape = RoundedCornerShape(16.dp),
                placeholder = { AppText(label, color = AppColors.muted) },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
            )
            AppIconButton(
                Icons.Regular.Send,
                appString(if (state.submissionPending) AppString.Retry else AppString.ChatSend),
                { onEvent(ChatThreadEvent.SendRequested) },
                enabled = state.canSend,
                loading = state.saving,
            )
        }

        state.draftFailure?.let { failure ->
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
