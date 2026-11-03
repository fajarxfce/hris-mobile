@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.collaboration.presentation.inbox.*
import dev.fajar.starter.collaboration.presentation.inbox.pages.ChatInboxPage
import dev.fajar.starter.collaboration.presentation.participants.*
import dev.fajar.starter.collaboration.presentation.participants.pages.ChatParticipantsPage
import dev.fajar.starter.collaboration.presentation.replies.*
import dev.fajar.starter.collaboration.presentation.replies.pages.SavedRepliesPage
import dev.fajar.starter.collaboration.presentation.setup.*
import dev.fajar.starter.collaboration.presentation.setup.pages.ChatSetupPage
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Serializable data object ChatInboxRoute

@Serializable data object ChatSavedRepliesRoute

@Serializable data class ChatSavedReplyRoute(val id: String)

@Serializable data object ChatParticipantsRoute

@Serializable data object ChatSetupRoute

@Serializable data class ChatThreadRoute(val conversationId: String)

fun NavGraphBuilder.collaborationRoutes(
    workspaceId: String,
    onNewChat: () -> Unit,
    onSavedReplies: () -> Unit,
    onSavedReply: (String) -> Unit,
    onConversation: (String) -> Unit,
    onSelectedConversation: (String) -> Unit,
    onSetup: () -> Unit,
    onBack: () -> Unit,
    onThreadFocus:
        (
            dev.fajar.starter.collaboration.domain.entities.CollaborationOwner, String,
        ) -> AutoCloseable? =
        { _, _ ->
            null
        },
) {
    composable<ChatInboxRoute> {
        ChatInboxContent(workspaceId, onNewChat, onSavedReplies, onConversation, onBack)
    }
    composable<ChatSavedRepliesRoute> {
        SavedRepliesEntry(workspaceId, "", onSavedReply, onConversation, onBack)
    }
    composable<ChatSavedReplyRoute> { entry ->
        SavedRepliesEntry(
            workspaceId,
            entry.toRoute<ChatSavedReplyRoute>().id,
            onSavedReply,
            onConversation,
            onBack,
        )
    }
    composable<ChatParticipantsRoute> {
        ChatParticipantsEntry(workspaceId, onSelectedConversation, onBack)
    }
    composable<ChatSetupRoute> { ChatSetupEntry(workspaceId, onBack) }
    composable<ChatThreadRoute> { entry ->
        ChatThreadEntry(
            workspaceId,
            entry.toRoute<ChatThreadRoute>().conversationId,
            onSetup,
            onBack,
            onThreadFocus,
        )
    }
}

@Composable
fun ChatInboxContent(
    workspaceId: String,
    onNewChat: () -> Unit,
    onSavedReplies: () -> Unit,
    onConversation: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val vm = koinViewModel<ChatInboxViewModel>(parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(vm) {
        vm.onEvent(ChatInboxEvent.Entered)
        onStopOrDispose { vm.onEvent(ChatInboxEvent.Stopped) }
    }
    CollectEffects(vm.effects) {
        when (it) {
            ChatInboxEffect.Back -> onBack?.invoke()
            ChatInboxEffect.NewChat -> onNewChat()
            ChatInboxEffect.SavedReplies -> onSavedReplies()
            ChatInboxEffect.ScrollToStart -> list.requestScrollToItem(0)
            is ChatInboxEffect.OpenConversation -> onConversation(it.id)
        }
    }
    ChatInboxPage(state, list, vm::onEvent, onBack = onBack)
}

@Composable
private fun ChatParticipantsEntry(
    workspaceId: String,
    onConversation: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm = koinViewModel<ChatParticipantsViewModel>(parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(vm) {
        vm.onEvent(ChatParticipantsEvent.Entered)
        onStopOrDispose { vm.onEvent(ChatParticipantsEvent.Stopped) }
    }
    CollectEffects(vm.effects) {
        when (it) {
            ChatParticipantsEffect.Back -> onBack()
            ChatParticipantsEffect.ScrollToStart -> list.requestScrollToItem(0)
            is ChatParticipantsEffect.OpenConversation -> onConversation(it.id)
        }
    }
    ChatParticipantsPage(state, list, vm::onEvent)
}

@Composable
private fun ChatSetupEntry(workspaceId: String, onBack: () -> Unit) {
    val vm = koinViewModel<ChatSetupViewModel>(parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.onEvent(ChatSetupEvent.Entered)
        onStopOrDispose { vm.onEvent(ChatSetupEvent.Stopped) }
    }
    CollectEffects(vm.effects) {
        when (it) {
            ChatSetupEffect.Back -> onBack()
            is ChatSetupEffect.Ready -> onBack()
        }
    }
    ChatSetupPage(state, vm::onEvent)
}

@Composable
private fun ChatThreadEntry(
    workspaceId: String,
    conversationId: String,
    onSetup: () -> Unit,
    onBack: () -> Unit,
    onThreadFocus:
        (
            dev.fajar.starter.collaboration.domain.entities.CollaborationOwner, String,
        ) -> AutoCloseable?,
) {
    val vm =
        koinViewModel<ChatThreadViewModel>(
            parameters = { parametersOf(workspaceId, conversationId) }
        )
    val state by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(vm) {
        vm.onEvent(ChatThreadEvent.Entered)
        onStopOrDispose { vm.onEvent(ChatThreadEvent.Stopped) }
    }
    CollectEffects(vm.effects) {
        when (it) {
            ChatThreadEffect.Back -> onBack()
            ChatThreadEffect.Setup -> onSetup()
            ChatThreadEffect.ScrollToNewest -> list.requestScrollToItem(0)
        }
    }
    ChatThreadFocusObserver(state, conversationId, onThreadFocus)
    ChatThreadViewportObserver(state, list, vm::onEvent)
    ChatThreadPage(state, list, vm::onEvent)
}

@Composable
private fun SavedRepliesEntry(
    workspaceId: String,
    replyId: String,
    onReply: (String) -> Unit,
    onConversation: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm =
        koinViewModel<SavedRepliesViewModel>(parameters = { parametersOf(workspaceId, replyId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(vm) {
        vm.onEvent(SavedRepliesEvent.Entered)
        onStopOrDispose { vm.onEvent(SavedRepliesEvent.Stopped) }
    }
    CollectEffects(vm.effects) {
        when (it) {
            SavedRepliesEffect.Back -> onBack()
            is SavedRepliesEffect.OpenReply -> onReply(it.id)
            is SavedRepliesEffect.OpenChat -> onConversation(it.conversationId)
        }
    }
    BackHandler { vm.onEvent(SavedRepliesEvent.BackRequested) }
    SavedRepliesPage(state, list, vm::onEvent)
}
