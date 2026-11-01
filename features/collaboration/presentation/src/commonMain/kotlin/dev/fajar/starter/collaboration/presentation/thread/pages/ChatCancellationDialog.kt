package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.ChatCancellationDialog
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadEvent
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*

@Composable
internal fun ChatCancellationDialog(
    state: ChatCancellationDialog,
    onEvent: (ChatThreadEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.ChatCancelTitle),
        appString(AppString.Close),
        { onEvent(ChatThreadEvent.CancellationDismissed) },
    ) {
        Column(
            Modifier.weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppText(appString(AppString.ChatCancelExplanation))
            AppText(
                state.text,
                color = AppColors.muted,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.unavailable) AppFeedback(appString(AppString.ChatCancellationUnavailable))
            else if (state.failure != null)
                AppFeedback(appString(AppString.ChatCancellationPending))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!state.unavailable)
                AppButton(
                    appString(
                        if (state.failure == null) AppString.ChatCancelSend else AppString.Retry
                    ),
                    { onEvent(ChatThreadEvent.CancellationConfirmed) },
                    modifier = Modifier.fillMaxWidth(),
                    loading = state.busy,
                )
            else
                AppButton(
                    appString(AppString.Close),
                    { onEvent(ChatThreadEvent.CancellationDismissed) },
                    Modifier.fillMaxWidth(),
                )
        }
    }
}
