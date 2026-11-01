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
    Row(
        modifier.fillMaxWidth().background(AppColors.surface).padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextField(
            value = state.draft,
            onValueChange = { onEvent(ChatThreadEvent.DraftChanged(it)) },
            modifier =
                Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp).semantics {
                    contentDescription = label
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
}
