package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.fajar.starter.collaboration.presentation.spacecontacts.*
import dev.fajar.starter.collaboration.presentation.spacecontacts.pages.SpaceContactsPage
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Browse/search share one local model while each navigation entry retains its own viewport. */
@Composable
fun SpaceContactsContent(
    root: SpaceChatRoot,
    surface: SpaceContactsSurface,
    onContact: (String) -> Unit,
    search: @Composable (SpaceContactsState, (SpaceContactsEvent) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    bottomPadding: Dp = 8.dp,
) {
    if (!root.isOpen) return
    key(root, surface) {
        val model =
            koinViewModel<SpaceContactsViewModel>(
                viewModelStoreOwner = root.store,
                key = "space-contacts:" + spaceChatThreadModelKey(root.access, "contacts"),
                parameters = { parametersOf(root.access) },
            )
        val state by model.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val open by rememberUpdatedState(onContact)
        if (active) {
            LifecycleResumeEffect(model, surface) {
                model.onEvent(SpaceContactsEvent.Entered(surface))
                onPauseOrDispose { model.onEvent(SpaceContactsEvent.Stopped(surface)) }
            }
            LaunchedEffect(root, model, surface, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    model.effects.collect { effect ->
                        when (effect) {
                            is SpaceContactsEffect.OpenContact ->
                                if (root.isOpen && effect.surface == surface) open(effect.accountId)
                        }
                    }
                }
            }
        }
        SpaceContactsPage(
            state,
            list,
            model::onEvent,
            search = { search(state, model::onEvent) },
            modifier = modifier,
            bottomPadding = bottomPadding,
            surface = surface,
        )
    }
}
