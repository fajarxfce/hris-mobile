package dev.fajar.starter.collaboration.presentation.thread.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.chatRequiresReconnect
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
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
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.now().toLocalDateTime(timeZone).date,
    composerInputModifier: Modifier = Modifier,
) {
    state.forwarding?.let {
        ChatForwardPage(it, onEvent, modifier)
        return
    }
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
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(ChatThreadEvent.BackRequested) },
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
                        !state.syncing &&
                        state.connection == ChatThreadConnection.CONNECTED &&
                        state.rows.isEmpty() &&
                        state.unsentReplies.isEmpty() &&
                        failure == null &&
                        !state.historyIncomplete
                if (
                    failure != null ||
                        state.submissionPending ||
                        state.connection == ChatThreadConnection.SETUP_REQUIRED
                )
                    item("status") { ChatThreadStatus(state, onEvent) }
                items(
                    state.unsentReplies,
                    key = { "unsent-${it.id}" },
                    contentType = { "unsent" },
                ) { reply ->
                    ChatUnsentReplyBubble(
                        reply,
                        { onEvent(ChatThreadEvent.UnsentReplySelected(reply.id)) },
                        enabled = !state.selecting && !state.leaving && state.localFailure == null,
                    )
                }
                if (state.unsentFailure != null)
                    item("unsent-error") {
                        AppTextButton(
                            appString(AppString.ChatUnsentUnavailable),
                            { onEvent(ChatThreadEvent.LocalReadRetryRequested) },
                        )
                    }
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
                    val date = chatMessageDate(row.timestamp, timeZone)
                    val older = state.rows.getOrNull(index + 1)
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (older == null || chatMessageDate(older.timestamp, timeZone) != date)
                            ChatDayDivider(date, today)
                        ChatMessageBubble(
                            row,
                            timeZone = timeZone,
                            selected = state.selectedMessages.any { sameChatMessage(it, row) },
                            selecting = state.selecting,
                            quoteContent =
                                if (row.replyToEventId == null) null
                                else {
                                    {
                                        ChatQuotePreview(
                                            state.quote(requireNotNull(row.replyToEventId)),
                                            state.selfSender,
                                            state.title,
                                        )
                                    }
                                },
                            enabled =
                                state.localFailure == null &&
                                    state.editing == null &&
                                    !state.leaving &&
                                    state.deletionDialog == null &&
                                    state.cancellationDialog == null,
                            onSelect = { onEvent(ChatThreadEvent.MessageSelected(row.key)) },
                        )
                    }
                }
                if (state.loadingOlder && state.rows.isNotEmpty())
                    item("loading") {
                        Box(
                            Modifier.fillMaxWidth().padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppProgressRing(appString(AppString.ChatOlderMessages))
                        }
                    }
                if (state.hasOlder && state.rows.isNotEmpty())
                    item("older") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AppTextButton(
                                appString(AppString.ChatOlderMessages),
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
            ChatComposer(state, onEvent, inputModifier = composerInputModifier)
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
        (state.localFailure
                ?: state.historyFailure
                ?: state.failure?.takeUnless(::chatCanReconnect))
            ?.takeUnless {
                state.localFailure == null &&
                    state.connection == ChatThreadConnection.SETUP_REQUIRED &&
                    chatRequiresReconnect(it)
            }
            ?.let {
                AppFeedback(
                    if (state.localFailure == null && chatCanReconnect(it))
                        appString(AppString.ChatEarlierUnavailable)
                    else failureText(it)
                )
            }
        if (state.submissionPending)
            AppText(appString(AppString.ChatSaveUnconfirmed), color = AppColors.muted)
        if (state.connection == ChatThreadConnection.SETUP_REQUIRED)
            AppTextButton(
                appString(
                    if (chatRequiresReconnect(state.failure)) AppString.ChatReconnectAction
                    else AppString.ChatSetupActivate
                ),
                { onEvent(ChatThreadEvent.SetupRequested) },
            )
        else if (
            state.localFailure != null ||
                state.historyFailure != null ||
                state.failure?.let { !chatCanReconnect(it) } == true
        )
            AppTextButton(
                appString(AppString.Retry),
                { onEvent(ChatThreadEvent.RefreshRequested) },
                enabled = !state.syncing && state.connection != ChatThreadConnection.CONNECTING,
            )
    }
}
