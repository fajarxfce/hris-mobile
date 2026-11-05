package dev.fajar.starter.collaboration.presentation.inbox.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.CollaborationInboxPreview
import dev.fajar.starter.collaboration.presentation.inbox.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.widgets.ChatPersonRow
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowLeft
import kotlin.time.Clock
import kotlinx.datetime.*

@Composable
fun ChatInboxPage(
    state: ChatInboxState,
    listState: LazyListState,
    onEvent: (ChatInboxEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = { onEvent(ChatInboxEvent.BackRequested) },
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.now().toLocalDateTime(zone).date,
    topBar: (@Composable () -> Unit)? = null,
) {
    AppLazyPage(
        modifier = modifier,
        state = listState,
        spacing = 0.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(vertical = 8.dp),
        topBar =
            topBar
                ?: {
                    AppTopBar(
                        title = appString(AppString.ChatTitle),
                        subtitle =
                            if (state.refreshFailure?.let(::chatCanReconnect) == true)
                                appString(AppString.ChatOffline)
                            else null,
                        navigation =
                            if (onBack == null) null
                            else {
                                {
                                    AppIconButton(
                                        Icons.Regular.ArrowLeft,
                                        appString(AppString.Back),
                                        onBack,
                                    )
                                }
                            },
                        actions = {
                            AppIconButton(
                                Icons.Regular.Add,
                                appString(AppString.ChatNew),
                                { onEvent(ChatInboxEvent.NewChatRequested) },
                                enabled = !state.restricted,
                            )
                        },
                    )
                },
    ) {
        if (state.orphanedUnsentCount > 0)
            item("saved-replies") {
                AppTextButton(
                    appString(AppString.ChatUnsentCount, state.orphanedUnsentCount.toString()),
                    { onEvent(ChatInboxEvent.SavedRepliesRequested) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    enabled = !state.restricted,
                )
            }
        val initialAcquisition = state.refreshing && state.page?.checkpoint == null
        if (
            (state.loading && state.page == null) ||
                (state.page?.items?.isEmpty() == true && initialAcquisition)
        )
            item("loading") { AppLoading() }
        val failure =
            state.failure
                ?: state.previewFailure
                ?: state.refreshFailure?.takeUnless(::chatCanReconnect)
        if (failure != null)
            item("failure") {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppFeedback(failureText(failure))
                    if (state.page?.items?.isNotEmpty() == true)
                        AppText(appString(AppString.ChatSaved), color = AppColors.muted)
                    AppTextButton(
                        appString(AppString.Retry),
                        { onEvent(ChatInboxEvent.RefreshRequested) },
                        enabled = !state.refreshing,
                    )
                }
            }
        if (
            state.page?.items?.isEmpty() == true &&
                !state.loading &&
                !initialAcquisition &&
                failure == null
        )
            item("empty") {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppText(appString(AppString.ChatEmpty), style = FluentTheme.typography.subtitle)
                    AppText(appString(AppString.ChatEmptyHint), color = AppColors.muted)
                    AppButton(
                        appString(AppString.ChatNew),
                        { onEvent(ChatInboxEvent.NewChatRequested) },
                    )
                }
            }
        items(
            state.page?.items.orEmpty(),
            key = { it.conversation.id },
            contentType = { "conversation" },
        ) { item ->
            val preview = state.previews[item.conversation.id]
            val draft = preview?.draft?.takeIf { it.text.isNotEmpty() }
            val timestamp = draft?.savedAt?.toEpochMilliseconds() ?: preview?.message?.timestamp
            ChatPersonRow(
                item.peer.displayName,
                { onEvent(ChatInboxEvent.ConversationSelected(item.conversation.id)) },
                enabled = !state.restricted,
                supporting = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val count = state.unsentCounts[item.conversation.id] ?: 0
                        if (count > 0)
                            AppText(
                                appString(AppString.ChatUnsentCount, count.toString()),
                                color = AppColors.error,
                            )
                        else preview?.let { InboxPreviewText(it) }
                    }
                },
                trailing = timestamp?.let { { InboxTimestamp(it, today, zone) } },
            )
        }
        if (state.previous.isNotEmpty() || state.hasMore)
            item("pagination") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (state.previous.isNotEmpty())
                        AppTextButton(
                            appString(AppString.ChatPrevious),
                            { onEvent(ChatInboxEvent.PreviousRequested) },
                            enabled = !state.loading && !state.refreshing,
                        )
                    if (state.hasMore)
                        AppTextButton(
                            appString(AppString.ChatNext),
                            { onEvent(ChatInboxEvent.NextRequested) },
                            enabled = !state.loading && !state.refreshing,
                        )
                }
            }
    }
}

@Composable
private fun InboxPreviewText(preview: CollaborationInboxPreview) {
    val draft = preview.draft?.takeIf { it.text.isNotEmpty() }
    val text =
        when {
            draft != null -> "${appString(AppString.ChatDraftLabel)}: ${draft.text}"
            preview.message?.redacted == true -> appString(AppString.ChatDeleted)
            else -> preview.message?.text
        }
    if (text != null)
        AppText(
            text,
            style = FluentTheme.typography.body,
            color = if (draft != null) AppColors.accent else AppColors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
}

@Composable
private fun InboxTimestamp(timestamp: Long, today: LocalDate, zone: TimeZone) {
    val date = chatMessageDate(timestamp, zone)
    val text =
        if (date == today) chatMessageTime(timestamp, zone)
        else chatDayResource(date, today)?.let { appString(it) } ?: chatCalendarDate(date)
    AppText(
        text,
        Modifier.widthIn(max = 140.dp),
        style = FluentTheme.typography.caption,
        color = AppColors.muted,
    )
}
