package dev.fajar.starter.collaboration.presentation.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.koin.compose.viewmodel.koinViewModel

/** Supplies the same account/Group resources around the Company navigation subtree. */
@Composable
fun SpaceChatRootContent(
    owner: SpaceChatRootOwner = koinViewModel(),
    content: @Composable (SpaceChatRootState, SpaceChatRoot?) -> Unit,
) {
    val state by owner.state.collectAsStateWithLifecycle()
    val store =
        owner.store?.takeIf {
            !it.closed && it.access == state.access && owner.state.value.lifetime == state.lifetime
        }
    val root =
        if (store == null) null
        else
            key(store, state.lifetime) {
                val graph =
                    viewModel(viewModelStoreOwner = store, key = "space-thread-graph") {
                        SpaceChatNavigationOwner(store.access)
                    }
                remember(store, graph) { SpaceChatRoot(store, graph) }
            }
    content(state, root)
}

/** A composition handle cannot reopen a retired Group store. */
class SpaceChatRoot
internal constructor(
    internal val store: SpaceChatRootStore,
    internal val graph: SpaceChatNavigationOwner,
) {
    val access
        get() = store.access

    val isOpen
        get() = !store.closed
}
