package dev.fajar.starter

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.fajar.starter.app.StarterApp
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.storage.BrowserPreferenceStore
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val container = createAppContainer(BrowserPreferenceStore())
    ComposeViewport(document.body!!) { StarterApp(container) }
}
