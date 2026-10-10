package dev.fajar.starter.notifications.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.notifications.presentation.inbox.*
import dev.fajar.starter.notifications.presentation.inbox.pages.NotificationPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel

// Compose 1.9 has no common plain-text ClipEntry constructor for all four targets.
@Suppress("DEPRECATION")
fun NavGraphBuilder.notificationRoutes(onBack: () -> Unit, onDestination: (String) -> Unit) {
    composable<NotificationRoute> {
        val clipboard = LocalClipboardManager.current
        val viewModel = koinViewModel<NotificationViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            viewModel.onEvent(NotificationEvent.Resumed)
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                is NotificationEffect.CopyToken -> clipboard.setText(AnnotatedString(effect.token))
                NotificationEffect.Back -> onBack()
                is NotificationEffect.OpenDestination -> onDestination(effect.destination)
            }
        }
        NotificationPage(state, viewModel::onEvent)
    }
}
