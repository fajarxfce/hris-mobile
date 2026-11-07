@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
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
import kotlinx.coroutines.launch
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
    threadGraphOwner: () -> ViewModelStoreOwner,
    threadAccessHint: CollaborationLocalAccess? = null,
    onThreadFocus:
        (
            dev.fajar.starter.collaboration.domain.entities.CollaborationOwner, String,
        ) -> AutoCloseable? =
        { _, _ ->
            null
        },
) {
    composable<ChatInboxRoute> {
        ChatInboxContent(
            workspaceId,
            onNewChat,
            onSavedReplies,
            onConversation,
            onBack,
            threadGraphOwner = threadGraphOwner,
        )
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
        val graphOwner = remember(entry) { threadGraphOwner() }
        val stores = viewModel(viewModelStoreOwner = graphOwner) { ChatThreadViewModelStores() }
        val conversationId = entry.toRoute<ChatThreadRoute>().conversationId
        val owner = viewModel(viewModelStoreOwner = entry) { stores.open(conversationId) }
        ChatThreadEntry(
            workspaceId,
            conversationId,
            owner,
            threadAccessHint,
            onSetup,
            onBack,
            onThreadFocus,
            onSavedReply,
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
    topBar: (@Composable () -> Unit)? = null,
    active: Boolean = true,
    bottomPadding: Dp = 8.dp,
    threadGraphOwner: (() -> ViewModelStoreOwner)? = null,
) {
    val vm = koinViewModel<ChatInboxViewModel>(parameters = { parametersOf(workspaceId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    if (active) {
        if (threadGraphOwner != null) {
            val graphOwner = remember(workspaceId) { threadGraphOwner() }
            val stores = viewModel(viewModelStoreOwner = graphOwner) { ChatThreadViewModelStores() }
            PrepareVisibleChatThreads(workspaceId, state, list, stores)
        }
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
    }
    ChatInboxPage(
        state,
        list,
        vm::onEvent,
        onBack = onBack,
        topBar = topBar,
        bottomPadding = bottomPadding,
    )
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

@Suppress("DEPRECATION")
@Composable
private fun ChatThreadEntry(
    workspaceId: String,
    conversationId: String,
    owner: ChatThreadViewModelOwner,
    accessHint: CollaborationLocalAccess?,
    onSetup: () -> Unit,
    onBack: () -> Unit,
    onThreadFocus:
        (
            dev.fajar.starter.collaboration.domain.entities.CollaborationOwner, String,
        ) -> AutoCloseable?,
    onSavedReply: (String) -> Unit,
) {
    val vm =
        koinViewModel<ChatThreadViewModel>(
            viewModelStoreOwner = owner,
            parameters = { parametersOf(workspaceId, conversationId) },
        )
    owner.bind(vm)
    val state by vm.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val effectsScope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }
    LaunchedEffect(state.selecting) {
        if (state.selecting) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LifecycleStartEffect(vm) {
        vm.onEvent(ChatThreadEvent.Entered(accessHint))
        onStopOrDispose { vm.onEvent(ChatThreadEvent.Stopped) }
    }
    CollectEffects(vm.effects) {
        when (it) {
            ChatThreadEffect.Back -> onBack()
            ChatThreadEffect.Setup -> onSetup()
            is ChatThreadEffect.OpenReply -> onSavedReply(it.id)
            ChatThreadEffect.ScrollToNewest -> list.requestScrollToItem(0)
            ChatThreadEffect.FocusComposer ->
                effectsScope.launch {
                    withFrameNanos {}
                    if (vm.state.value.canCompose) {
                        composerFocus.requestFocus()
                        keyboard?.show()
                    }
                }
            is ChatThreadEffect.CopyMessages ->
                effectsScope.launch {
                    if (vm.confirmCopy(it)) clipboard.setText(AnnotatedString(it.text))
                }
        }
    }
    BackHandler { vm.onEvent(ChatThreadEvent.BackRequested) }
    ChatThreadFocusObserver(state, conversationId, onThreadFocus)
    ChatThreadViewport(state, list, vm::onEvent)
    ChatThreadPage(
        state,
        list,
        vm::onEvent,
        composerInputModifier = Modifier.focusRequester(composerFocus),
    )
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
