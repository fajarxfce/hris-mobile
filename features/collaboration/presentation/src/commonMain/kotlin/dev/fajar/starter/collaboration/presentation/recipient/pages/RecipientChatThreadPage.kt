package dev.fajar.starter.collaboration.presentation.recipient.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.recipient.*
import dev.fajar.starter.collaboration.presentation.spacethread.pages.SpaceChatQuotePreview
import dev.fajar.starter.collaboration.presentation.thread.chatMessageDate
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatDayDivider
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatMessageBubble
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.Copy
import kotlin.time.Clock
import kotlinx.datetime.*

/**
 * State and typed actions only. The route owns acquisition, scrolling effects and clipboard I/O.
 */
@Composable
fun RecipientChatThreadPage(
    state: RecipientChatThreadState,
    listState: LazyListState,
    onEvent: (RecipientChatThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
    peerName: String? = null,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.now().toLocalDateTime(timeZone).date,
    composerInputModifier: Modifier = Modifier,
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
            RecipientChatHeader(state, peerName, onEvent)
            if (state.selectionLimitReached) AppFeedback(appString(AppString.ChatSelectionLimit))
            if (state.actionFailure != null) AppFeedback(appString(AppString.ChatCopyUnavailable))
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                itemsIndexed(
                    state.rows,
                    key = { _, row -> row.identity.stableKey },
                    contentType = { _, _ -> "message" },
                ) { index, row ->
                    val date = chatMessageDate(row.bubble.timestamp, timeZone)
                    val older = state.rows.getOrNull(index + 1)
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (
                            older == null ||
                                chatMessageDate(older.bubble.timestamp, timeZone) != date
                        )
                            ChatDayDivider(date, today)
                        ChatMessageBubble(
                            row.bubble,
                            timeZone = timeZone,
                            selected = row.identity in state.selection,
                            selecting = state.selecting,
                            enabled =
                                state.restriction == null &&
                                    state.projectionFailure == null &&
                                    !state.leaving,
                            quoteContent =
                                row.bubble.replyToEventId?.let { target ->
                                    { SpaceChatQuotePreview(state.thread, target) }
                                },
                            onSelect = {
                                onEvent(RecipientChatThreadEvent.MessageSelected(row.identity))
                            },
                        )
                    }
                }
                if (state.rows.isEmpty())
                    item("empty") {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                state.restriction != null ||
                                    state.historyFailure != null ||
                                    state.sourceFailure != null ||
                                    state.destinationFailure != null ||
                                    state.projectionFailure != null ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        AppText(
                                            appString(AppString.ChatMessagesUnavailable),
                                            color = AppColors.muted,
                                        )
                                        AppTextButton(
                                            appString(AppString.Retry),
                                            { onEvent(RecipientChatThreadEvent.RetryRequested) },
                                            enabled = !state.leaving,
                                        )
                                    }
                                state.loading -> AppProgressRing(appString(AppString.ChatTitle))
                                else ->
                                    AppText(
                                        appString(AppString.ChatStartMessage),
                                        color = AppColors.muted,
                                    )
                            }
                        }
                    }
            }
            RecipientChatComposer(state, onEvent, inputModifier = composerInputModifier)
        }
    }
}

@Composable
private fun RecipientChatHeader(
    state: RecipientChatThreadState,
    peerName: String?,
    onEvent: (RecipientChatThreadEvent) -> Unit,
) {
    if (state.selecting)
        AppTopBar(
            appString(AppString.ChatSelectedCount, state.selection.size.toString()),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            navigation = {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.ChatClearSelection),
                    { onEvent(RecipientChatThreadEvent.SelectionCleared) },
                )
            },
            actions = {
                AppIconButton(
                    Icons.Regular.Copy,
                    appString(AppString.ChatCopyMessages),
                    { onEvent(RecipientChatThreadEvent.CopySelected) },
                    enabled = state.canCopySelection,
                )
            },
        )
    else
        AppTopBar(
            if (state.restriction != null) appString(AppString.ChatTitle)
            else
                peerName
                    ?: (state.thread?.participants?.contact as? AppResult.Success)
                        ?.value
                        ?.displayName
                    ?: appString(AppString.ChatTitle),
            navigation = {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.Back),
                    { onEvent(RecipientChatThreadEvent.BackRequested) },
                )
            },
        )
}
