package dev.fajar.starter.security.presentation.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.platform.AppPrivacyProtection
import dev.fajar.starter.security.presentation.lock.*
import dev.fajar.starter.security.presentation.lock.pages.AppLockPage
import org.koin.compose.viewmodel.koinViewModel

/** Composition owns lifecycle/gesture registrations; the MVI owner controls their policy. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val viewModel = koinViewModel<AppLockViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppPrivacyProtection(state.enabled)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onEvent(AppLockEvent.Foregrounded)
                Lifecycle.Event.ON_STOP -> viewModel.onEvent(AppLockEvent.Backgrounded)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onEvent(AppLockEvent.Backgrounded)
        }
    }
    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent {
                viewModel.onEvent(AppLockEvent.InteractionReceived)
                false
            }
            .pointerInput(viewModel) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    viewModel.onEvent(AppLockEvent.InteractionReceived)
                }
            }
    ) {
        when {
            state.loading -> AppPage { AppLoading() }
            state.locked -> AppLockPage(state, viewModel::onEvent)
            else -> content()
        }
    }
}
