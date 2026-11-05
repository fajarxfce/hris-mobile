package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.*

@Composable
internal fun ChatSelectionBar(
    state: ChatThreadState,
    onEvent: (ChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = appString(AppString.ChatSelectedCount, state.selectedMessages.size.toString())
    AppTopBar(
        count,
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        navigation = {
            AppIconButton(
                Icons.Regular.ArrowLeft,
                appString(AppString.ChatClearSelection),
                { onEvent(ChatThreadEvent.SelectionCleared) },
            )
        },
        actions = {
            AppIconButton(
                Icons.Regular.Copy,
                appString(AppString.ChatCopyMessages),
                { onEvent(ChatThreadEvent.CopySelected) },
                enabled = state.canCopySelection,
            )
            val queued =
                state.selectedMessages.singleOrNull()?.takeIf { it.canCancel && it.localId != null }
            if (queued != null)
                AppIconButton(
                    Icons.Regular.Dismiss,
                    appString(AppString.ChatCancelSend),
                    { onEvent(ChatThreadEvent.CancelRequested(requireNotNull(queued.localId))) },
                )
            else
                AppIconButton(
                    Icons.Regular.Delete,
                    appString(AppString.ChatDeleteMessages),
                    { onEvent(ChatThreadEvent.DeleteSelected) },
                    enabled = state.canDeleteSelection,
                )
        },
    )
}

@Composable
internal fun ChatLocalDeletionDialog(
    state: ChatDeletionDialog,
    onEvent: (ChatThreadEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.ChatDeleteMessages),
        appString(AppString.Close),
        { onEvent(ChatThreadEvent.DeletionDismissed) },
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(appString(AppString.ChatDeleteLocalExplanation, state.count.toString()))
            if (state.failure != null) AppFeedback(appString(AppString.ChatDeleteLocalPending))
        }
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppButton(
                appString(
                    if (state.failure == null) AppString.ChatDeleteForMe else AppString.Retry
                ),
                { onEvent(ChatThreadEvent.DeletionConfirmed) },
                Modifier.fillMaxWidth(),
                loading = state.busy,
            )
            AppTextButton(
                appString(AppString.Cancel),
                { onEvent(ChatThreadEvent.DeletionDismissed) },
                Modifier.fillMaxWidth(),
            )
        }
    }
}
