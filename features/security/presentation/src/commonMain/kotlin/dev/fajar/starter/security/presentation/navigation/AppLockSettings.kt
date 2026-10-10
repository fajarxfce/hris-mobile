package dev.fajar.starter.security.presentation.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.security.presentation.settings.*
import dev.fajar.starter.security.presentation.settings.widgets.AppLockSettingsCard
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppLockSettings() {
    val viewModel = koinViewModel<LockSettingsViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onEvent(LockSettingsEvent.Deactivated)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onEvent(LockSettingsEvent.Deactivated)
        }
    }
    AppLockSettingsCard(state, viewModel::onEvent)
}
