package dev.fajar.starter

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.ComposeUIViewController
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.storage.ApplePreferenceStore

fun MainViewController() =
    createAppContainer(ApplePreferenceStore()).let { container ->
        ComposeUIViewController {
            DisposableEffect(container) { onDispose { container.close() } }
            StarterApp(container)
        }
    }
