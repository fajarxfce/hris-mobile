package dev.fajar.starter

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.storage.DesktopPreferenceStore

fun main() {
    val container = createAppContainer(DesktopPreferenceStore())
    application {
        DisposableEffect(container) { onDispose { container.close() } }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Fluent Starter",
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            StarterApp(container)
        }
    }
}
