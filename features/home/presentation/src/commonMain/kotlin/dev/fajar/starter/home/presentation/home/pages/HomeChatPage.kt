package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add

/** Fixed category tabs, independently owned pages and one interruptible primary action. */
@Composable
internal fun HomeChatPage(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    conversations: @Composable (Boolean, Dp) -> Unit,
    contacts: @Composable (Boolean, Dp) -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            AppTabs(
                listOf(
                    AppTabItem(ChatSection.Conversations, appString(AppString.ChatConversations)),
                    AppTabItem(ChatSection.Contacts, appString(AppString.ChatContacts)),
                ),
                state.chatSection,
                { onEvent(HomeEvent.ChatSectionSelected(it)) },
                enabled = !state.working,
            )
            AppFloatingActionLayout(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                stateKey = state.chatSection,
                action = { visible ->
                    AppFloatingActionButton(
                        Icons.Regular.Add,
                        appString(AppString.ChatNew),
                        { onEvent(HomeEvent.NewChatRequested) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        visible = visible,
                        enabled = !state.working,
                    )
                },
            ) {
                AppSwipeTabContent(
                    items = ChatSection.entries,
                    selected = state.chatSection,
                    onSelected = { onEvent(HomeEvent.ChatSectionSelected(it)) },
                    stateKey = { it.name },
                    modifier = Modifier.fillMaxSize(),
                    enabled = !state.working,
                ) { section, active ->
                    when (section) {
                        ChatSection.Conversations -> conversations(active, 88.dp)
                        ChatSection.Contacts -> contacts(active, 88.dp)
                    }
                }
            }
        }
    }
}
