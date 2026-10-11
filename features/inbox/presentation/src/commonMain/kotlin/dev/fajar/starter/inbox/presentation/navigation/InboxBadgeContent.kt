package dev.fajar.starter.inbox.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.designsystem.components.AppCountBadge
import dev.fajar.starter.inbox.presentation.badge.*
import dev.fajar.starter.localization.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InboxBadgeContent(workspaceId: String) {
    val viewModel = koinViewModel<InboxBadgeViewModel>(parameters = { parametersOf(workspaceId) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(InboxBadgeEvent.Entered)
        onStopOrDispose { viewModel.onEvent(InboxBadgeEvent.Stopped) }
    }
    state.unreadCount?.let { count ->
        AppCountBadge(count, appString(AppString.InboxUnreadCount, count.toString()))
    }
}
