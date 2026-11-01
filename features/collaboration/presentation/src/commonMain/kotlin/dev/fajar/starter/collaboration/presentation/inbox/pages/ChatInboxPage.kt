package dev.fajar.starter.collaboration.presentation.inbox.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.inbox.*
import dev.fajar.starter.collaboration.presentation.widgets.ChatPersonRow
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun ChatInboxPage(
    state: ChatInboxState,
    listState: LazyListState,
    onEvent: (ChatInboxEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppLazyPage(
        modifier = modifier,
        state = listState,
        spacing = 0.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(vertical = 8.dp),
        topBar = {
            AppTopBar(
                title = appString(AppString.ChatTitle),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ChatInboxEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(ChatInboxEvent.RefreshRequested) },
                        enabled = !state.refreshing,
                        loading = state.refreshing,
                    )
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
        if (
            (state.loading && state.page == null) ||
                (state.page?.items?.isEmpty() == true && state.refreshing)
        )
            item("loading") { AppLoading() }
        val failure = state.failure ?: state.refreshFailure
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
                !state.refreshing &&
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
            ChatPersonRow(
                item.peer.displayName,
                { onEvent(ChatInboxEvent.ConversationSelected(item.conversation.id)) },
                enabled = !state.restricted,
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
