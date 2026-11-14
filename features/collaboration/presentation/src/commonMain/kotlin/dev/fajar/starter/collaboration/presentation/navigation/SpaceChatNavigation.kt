package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable data class SpaceChatThreadRoute(val conversationId: String)

/** The caller owns a separate account/Group graph, shared with visible-list preparation. */
fun NavGraphBuilder.spaceChatThreadRoutes(owner: SpaceChatNavigationOwner, onBack: () -> Unit) {
    composable<SpaceChatThreadRoute> { entry ->
        if (owner.closed) return@composable
        val conversationId = entry.toRoute<SpaceChatThreadRoute>().conversationId
        val lease =
            viewModel(
                viewModelStoreOwner = entry,
                key = spaceChatThreadModelKey(owner.access, conversationId),
            ) {
                owner.open(conversationId)
            }
        SpaceChatThreadContent(owner.access, conversationId, lease, onBack)
    }
}
