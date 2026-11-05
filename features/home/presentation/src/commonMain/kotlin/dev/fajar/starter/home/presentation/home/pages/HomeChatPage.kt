package dev.fajar.starter.home.presentation.home.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.home.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add

/** One communication destination; feature entries retain their own route-scoped state. */
@Composable
internal fun HomeChatPage(
    state: HomeState,
    onEvent: (HomeEvent) -> Unit,
    conversations: @Composable (@Composable () -> Unit) -> Unit,
    contacts: @Composable (@Composable () -> Unit) -> Unit,
) {
    val header: @Composable () -> Unit = {
        Column {
            AppTopBar(
                title = appString(AppString.ChatTitle),
                actions = {
                    AppIconButton(
                        Icons.Regular.Add,
                        appString(AppString.ChatNew),
                        { onEvent(HomeEvent.NewChatRequested) },
                        enabled = !state.working,
                    )
                },
            )
            AppTabs(
                listOf(
                    AppTabItem(ChatSection.Conversations, appString(AppString.ChatConversations)),
                    AppTabItem(ChatSection.Contacts, appString(AppString.ChatContacts)),
                ),
                state.chatSection,
                { onEvent(HomeEvent.ChatSectionSelected(it)) },
                enabled = !state.working,
            )
        }
    }
    AppTabContent(state.chatSection, stateKey = { it.name }) { section ->
        when (section) {
            ChatSection.Conversations -> conversations(header)
            ChatSection.Contacts -> contacts(header)
        }
    }
}
