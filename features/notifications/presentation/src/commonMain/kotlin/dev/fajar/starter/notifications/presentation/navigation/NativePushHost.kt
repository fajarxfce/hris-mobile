package dev.fajar.starter.notifications.presentation.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.notifications.presentation.nativepush.*
import dev.fajar.starter.notifications.presentation.nativepush.widgets.NativePushSettingsCard
import org.koin.compose.viewmodel.koinViewModel

private val LocalNativePush = staticCompositionLocalOf<NativePushViewModel?> { null }

@Composable
fun NativePushHost(content: @Composable () -> Unit) {
    val viewModel = koinViewModel<NativePushViewModel>()
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(NativePushEvent.Started)
        onStopOrDispose { viewModel.onEvent(NativePushEvent.Stopped) }
    }
    CompositionLocalProvider(LocalNativePush provides viewModel, content = content)
}

@Composable
fun NativePushSettings() {
    val viewModel = requireNotNull(LocalNativePush.current)
    val state by viewModel.state.collectAsStateWithLifecycle()
    NativePushSettingsCard(state, viewModel::onEvent)
}
