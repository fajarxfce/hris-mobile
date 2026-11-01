package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun ChatThreadPage(
    state: ChatThreadState,
    listState: LazyListState,
    onEvent: (ChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(AppColors.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            AppTopBar(
                title = state.title ?: appString(AppString.ChatTitle),
                subtitle =
                    appString(
                        when (state.connection) {
                            ChatThreadConnection.OFFLINE -> AppString.ChatOffline
                            ChatThreadConnection.CONNECTING -> AppString.ChatConnecting
                            ChatThreadConnection.SETUP_REQUIRED -> AppString.ChatSetupActivate
                            ChatThreadConnection.WAITING -> AppString.ChatRoomWaiting
                            ChatThreadConnection.CONNECTED -> AppString.ChatConnected
                        }
                    ),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ChatThreadEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(ChatThreadEvent.RefreshRequested) },
                        enabled =
                            state.connection != ChatThreadConnection.CONNECTING &&
                                !state.syncing &&
                                !state.loadingOlder,
                        loading =
                            state.connection == ChatThreadConnection.CONNECTING || state.syncing,
                    )
                },
            )
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                val failure = state.localFailure ?: state.historyFailure ?: state.failure
                if (
                    failure != null ||
                        state.submissionPending ||
                        state.connection == ChatThreadConnection.SETUP_REQUIRED
                )
                    item("status") { ChatThreadStatus(state, onEvent) }
                if (state.hasNewer)
                    item("newer") {
                        AppTextButton(
                            appString(AppString.ChatNewerMessages),
                            { onEvent(ChatThreadEvent.NewerRequested) },
                            enabled = !state.loading && !state.loadingOlder,
                        )
                    }
                items(state.rows, key = { it.key }, contentType = { "message" }) {
                    val id = it.localId
                    ChatMessageBubble(
                        it,
                        onCancel =
                            if (
                                it.canCancel &&
                                    id != null &&
                                    state.localFailure == null &&
                                    !state.leaving
                            )
                                ({ onEvent(ChatThreadEvent.CancelRequested(id)) })
                            else null,
                    )
                }
                if (state.loading || state.loadingOlder) item("loading") { AppLoading() }
                if (state.hasOlder)
                    item("older") {
                        AppTextButton(
                            appString(AppString.ChatOlderMessages),
                            { onEvent(ChatThreadEvent.OlderRequested) },
                            enabled = !state.loading && !state.loadingOlder,
                        )
                    }
                if (state.historyIncomplete)
                    item("history-incomplete") {
                        AppText(appString(AppString.ChatHistoryIncomplete), color = AppColors.muted)
                    }
                if (
                    !state.loading &&
                        !state.loadingOlder &&
                        state.rows.isEmpty() &&
                        failure == null &&
                        !state.historyIncomplete
                )
                    item("empty") {
                        AppText(
                            appString(AppString.ChatStartMessage),
                            color = AppColors.muted,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
            }
            if (state.browsingHistory)
                AppTextButton(
                    appString(AppString.ChatLatestMessages),
                    { onEvent(ChatThreadEvent.LatestRequested) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enabled = state.localFailure == null,
                )
            ChatComposer(state, onEvent)
        }
    }
    state.draftDialog?.let { dialog ->
        val title =
            appString(
                if (dialog == ChatDraftDialog.RELOAD) AppString.ChatDraftReload
                else AppString.ChatDraftLeave
            )
        AppDialog(
            title,
            appString(AppString.Close),
            { onEvent(ChatThreadEvent.DraftDialogDismissed) },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppText(
                    appString(
                        if (dialog == ChatDraftDialog.RELOAD) AppString.ChatDraftReloadExplanation
                        else AppString.ChatDraftLeaveExplanation
                    )
                )
                AppButton(
                    title,
                    { onEvent(ChatThreadEvent.DraftDialogConfirmed) },
                    enabled =
                        dialog == ChatDraftDialog.LEAVE || (!state.saving && !state.draftSaving),
                )
            }
        }
    }
    state.cancellationDialog?.let { ChatCancellationDialog(it, onEvent) }
}

@Composable
private fun ChatThreadStatus(state: ChatThreadState, onEvent: (ChatThreadEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (state.localFailure ?: state.historyFailure ?: state.failure)?.let {
            AppFeedback(failureText(it))
        }
        if (state.submissionPending)
            AppText(appString(AppString.ChatSaveUnconfirmed), color = AppColors.muted)
        if (state.connection == ChatThreadConnection.SETUP_REQUIRED)
            AppTextButton(
                appString(AppString.ChatSetupActivate),
                { onEvent(ChatThreadEvent.SetupRequested) },
            )
        else if (
            state.localFailure != null || state.historyFailure != null || state.failure != null
        )
            AppTextButton(
                appString(AppString.Retry),
                { onEvent(ChatThreadEvent.RefreshRequested) },
                enabled = !state.syncing && state.connection != ChatThreadConnection.CONNECTING,
            )
    }
}
