@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
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
import dev.fajar.starter.collaboration.presentation.recipient.*
import dev.fajar.starter.collaboration.presentation.recipient.pages.RecipientChatThreadPage
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** One leased local recipient model; entry does not provision a conversation or transport. */
@Composable
fun RecipientChatThreadContent(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    owner: ViewModelStoreOwner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(access, peerAccountId, owner) {
        RecipientChatThreadEntry(access, peerAccountId, owner, onBack, modifier)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun RecipientChatThreadEntry(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    owner: ViewModelStoreOwner,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    if (owner is SpaceChatThreadViewModelOwner && !owner.isOpen) return
    val model =
        koinViewModel<RecipientChatThreadViewModel>(
            viewModelStoreOwner = owner,
            key = spaceChatRecipientModelKey(access, peerAccountId),
            parameters = { parametersOf(access, peerAccountId) },
        )
    if (owner is SpaceChatThreadViewModelOwner) owner.bind(model)
    val state by model.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
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
        model.onEvent(RecipientChatThreadEvent.Entered)
        onStopOrDispose { model.onEvent(RecipientChatThreadEvent.Stopped) }
    }
    LaunchedEffect(model, lifecycleOwner, clipboard) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.effects.collectLatest { effect ->
                if (owner is SpaceChatThreadViewModelOwner && !owner.isOpen) return@collectLatest
                when (effect) {
                    is RecipientChatThreadEffect.Back -> if (model.confirmBack(effect)) goBack()
                    is RecipientChatThreadEffect.CopyMessages ->
                        if (model.confirmCopy(effect))
                            clipboard.setText(AnnotatedString(effect.text))
                }
            }
        }
    }
    BackHandler { model.onEvent(RecipientChatThreadEvent.BackRequested) }
    ChatMessageEndViewport(
        state.rows.firstOrNull()?.identity?.stableKey,
        !state.leaving && state.restriction == null,
        list,
    )
    RecipientChatThreadPage(state, list, model::onEvent, modifier)
}
