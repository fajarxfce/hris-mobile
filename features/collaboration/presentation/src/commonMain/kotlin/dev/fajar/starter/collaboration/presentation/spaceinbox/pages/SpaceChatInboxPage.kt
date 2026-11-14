package dev.fajar.starter.collaboration.presentation.spaceinbox.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceInboxEntry
import dev.fajar.starter.collaboration.presentation.spaceinbox.*
import dev.fajar.starter.collaboration.presentation.spaceinbox.widgets.SpaceChatInboxRow
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** The combined Chat/Contacts hub supplies tabs and FAB; this page owns only the scrolling list. */
@Composable
fun SpaceChatInboxPage(
    state: SpaceChatInboxState,
    entries: LazyPagingItems<CollaborationSpaceInboxEntry>,
    list: LazyListState,
    today: LocalDate,
    zone: TimeZone,
    onEvent: (SpaceChatInboxEvent) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 8.dp,
) {
    AppLazyPage(
        modifier,
        state = list,
        spacing = 0.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
    ) {
        if (state.restriction != null) {
            item("restricted") {
                Box(Modifier.padding(24.dp)) { AppFeedback(failureText(state.restriction)) }
            }
        } else {
            if (entries.itemCount == 0) {
                when (val load = entries.loadState.refresh) {
                    is LoadState.Loading -> item("initial") { AppLoading() }
                    is LoadState.Error ->
                        item("failure") {
                            Column(
                                Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val failure = (load.error as? SpaceChatInboxLoadException)?.failure
                                if (failure != null) AppFeedback(failureText(failure))
                                else AppText(appString(AppString.ChatPreviewUnavailable))
                                AppTextButton(appString(AppString.Retry), entries::retry)
                            }
                        }
                    is LoadState.NotLoading ->
                        item("empty") {
                            Column(
                                Modifier.padding(horizontal = 24.dp, vertical = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppText(
                                    appString(AppString.ChatEmpty),
                                    style = FluentTheme.typography.subtitle,
                                )
                                AppText(appString(AppString.ChatEmptyHint), color = AppColors.muted)
                            }
                        }
                }
            }
            items(
                count = entries.itemCount,
                key = entries.itemKey { it.conversationId },
                contentType = entries.itemContentType { "conversation" },
            ) { index ->
                entries[index]?.let { entry ->
                    SpaceChatInboxRow(
                        entry,
                        state.names[entry.conversationId] ?: appString(AppString.ChatContactLabel),
                        today,
                        zone,
                        { onEvent(SpaceChatInboxEvent.ConversationSelected(entry.conversationId)) },
                    )
                }
            }
            if (entries.itemCount > 0 && entries.loadState.append is LoadState.Error) {
                item("append-failure") {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        AppTextButton(appString(AppString.Retry), entries::retry)
                    }
                }
            }
        }
    }
}
