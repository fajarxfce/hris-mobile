@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.collaboration.presentation.spacethread.pages.SpaceChatThreadPage
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The caller owns this account/Group navigation store, independently of HR company selection. */
@Suppress("DEPRECATION")
@Composable
fun SpaceChatThreadContent(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    owner: ViewModelStoreOwner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(access, conversationId, owner) {
        SpaceChatThreadEntry(access, conversationId, owner, onBack, modifier)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun SpaceChatThreadEntry(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    owner: ViewModelStoreOwner,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val model =
        koinViewModel<SpaceChatThreadViewModel>(
            viewModelStoreOwner = owner,
            key =
                listOf(
                        access.audience,
                        access.accountId,
                        access.spaceId,
                        access.sessionId,
                        conversationId,
                    )
                    .joinToString("") { "${it.length}:$it" },
            parameters = { parametersOf(access, conversationId) },
        )
    val state by model.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val composerFocus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val goBack by rememberUpdatedState(onBack)
    LaunchedEffect(state.selecting) {
        if (state.selecting) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    LifecycleStartEffect(model) {
        model.onEvent(SpaceChatThreadEvent.Entered)
        onStopOrDispose { model.onEvent(SpaceChatThreadEvent.Stopped) }
    }
    LaunchedEffect(model, lifecycleOwner, clipboard, keyboard) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Stop or a newer explicit effect cancels an obsolete clipboard/focus operation.
            model.effects.collectLatest { effect ->
                when (effect) {
                    SpaceChatThreadEffect.Back -> goBack()
                    SpaceChatThreadEffect.ScrollToNewest -> list.requestScrollToItem(0)
                    SpaceChatThreadEffect.FocusComposer -> {
                        withFrameNanos {}
                        if (model.state.value.canCompose) {
                            composerFocus.requestFocus()
                            keyboard?.show()
                        }
                    }
                    is SpaceChatThreadEffect.CopyMessages -> {
                        if (model.confirmCopy(effect))
                            clipboard.setText(AnnotatedString(effect.text))
                    }
                }
            }
        }
    }
    BackHandler { model.onEvent(SpaceChatThreadEvent.BackRequested) }
    SpaceChatThreadViewport(state, list)
    SpaceChatThreadPage(
        state,
        list,
        model::onEvent,
        modifier,
        composerInputModifier = Modifier.focusRequester(composerFocus),
    )
}

/** Runs in the layout-owning composition, so a new bubble is visible in its first layout pass. */
@Composable
internal fun SpaceChatThreadViewport(state: SpaceChatThreadState, list: LazyListState) {
    val newest = state.rows.firstOrNull()?.identity
    var displayed by remember(list) { mutableStateOf(newest) }
    SideEffect {
        if (displayed != newest) {
            displayed = newest
            if (
                !state.leaving &&
                    state.restriction == null &&
                    !list.isScrollInProgress &&
                    list.firstVisibleItemIndex == 0 &&
                    list.firstVisibleItemScrollOffset == 0
            )
                list.requestScrollToItem(0)
        }
    }
}
