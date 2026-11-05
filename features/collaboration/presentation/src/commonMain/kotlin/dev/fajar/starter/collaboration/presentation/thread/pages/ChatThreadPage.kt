package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Checkmark
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun ChatThreadPage(
    state: ChatThreadState,
    listState: LazyListState,
    onEvent: (ChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
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
            if (state.selecting) ChatSelectionBar(state, onEvent)
            else
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
                        if (state.rows.isNotEmpty())
                            AppIconButton(
                                Icons.Regular.Checkmark,
                                appString(AppString.ChatSelectMessage),
                                { onEvent(ChatThreadEvent.SelectionStarted) },
                                enabled = state.localFailure == null && !state.leaving,
                            )
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(ChatThreadEvent.RefreshRequested) },
                            enabled =
                                state.connection != ChatThreadConnection.CONNECTING &&
                                    !state.syncing &&
                                    !state.loadingOlder,
                        )
                    },
                )
            if (state.selectionLimitReached) AppFeedback(appString(AppString.ChatSelectionLimit))
            if (state.selecting && state.selectedMessages.size > 1 && !state.canDeleteSelection)
                AppText(
                    appString(AppString.ChatSelectPendingAlone),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = AppColors.muted,
                )
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                val failure = state.localFailure ?: state.historyFailure ?: state.failure
                val emptyHistory =
                    !state.loading &&
                        !state.loadingOlder &&
                        state.rows.isEmpty() &&
                        failure == null &&
                        !state.historyIncomplete
                val historyAction =
                    if (state.rows.isEmpty()) AppString.ChatLoadMessages
                    else AppString.ChatOlderMessages
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
                itemsIndexed(
                    state.rows,
                    key = { _, row -> row.key },
                    contentType = { _, _ -> "message" },
                ) { index, row ->
                    val date = chatMessageDate(row.timestamp, TimeZone.currentSystemDefault())
                    val older = state.rows.getOrNull(index + 1)
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (
                            older == null ||
                                chatMessageDate(older.timestamp, TimeZone.currentSystemDefault()) !=
                                    date
                        )
                            ChatDayDivider(date, today)
                        ChatMessageBubble(
                            row,
                            selected = state.selectedMessages.any { sameChatMessage(it, row) },
                            selecting = state.selecting,
                            enabled =
                                state.localFailure == null &&
                                    !state.leaving &&
                                    state.deletionDialog == null &&
                                    state.cancellationDialog == null,
                            onSelect = { onEvent(ChatThreadEvent.MessageSelected(row.key)) },
                        )
                    }
                }
                if ((state.loading && state.rows.isEmpty()) || state.loadingOlder)
                    item("loading") {
                        Box(
                            Modifier.fillMaxWidth().padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppProgressRing(appString(historyAction))
                        }
                    }
                if (state.hasOlder && !emptyHistory)
                    item("older") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AppTextButton(
                                appString(historyAction),
                                { onEvent(ChatThreadEvent.OlderRequested) },
                                enabled = !state.loading && !state.loadingOlder,
                            )
                        }
                    }
                if (state.historyIncomplete)
                    item("history-incomplete") {
                        AppText(appString(AppString.ChatHistoryIncomplete), color = AppColors.muted)
                    }
                if (emptyHistory)
                    item("empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AppText(
                                appString(
                                    if (state.hasOlder) AppString.ChatNoSavedMessages
                                    else AppString.ChatStartMessage
                                ),
                                color = AppColors.muted,
                                textAlign = TextAlign.Center,
                            )
                            if (state.hasOlder)
                                AppTextButton(
                                    appString(AppString.ChatLoadMessages),
                                    { onEvent(ChatThreadEvent.OlderRequested) },
                                )
                        }
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
    state.deletionDialog?.let { ChatLocalDeletionDialog(it, onEvent) }
}

@Composable
private fun ChatDayDivider(date: LocalDate, today: LocalDate) {
    val resource = chatDayResource(date, today)
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        AppText(
            if (resource != null) appString(resource) else chatCalendarDate(date),
            modifier =
                Modifier.background(AppColors.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            style = FluentTheme.typography.caption,
            color = AppColors.muted,
        )
    }
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
