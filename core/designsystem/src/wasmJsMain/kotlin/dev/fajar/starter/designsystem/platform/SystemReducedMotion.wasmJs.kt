package dev.fajar.starter.designsystem.platform

import androidx.compose.runtime.*
import kotlinx.browser.window
import org.w3c.dom.events.Event

@Composable
actual fun isSystemInReducedMotion(): Boolean {
    val query = remember { window.matchMedia("(prefers-reduced-motion: reduce)") }
    var reduced by remember(query) { mutableStateOf(query.matches) }
    DisposableEffect(query) {
        val listener: (Event) -> Unit = { reduced = query.matches }
        query.addEventListener("change", listener)
        onDispose { query.removeEventListener("change", listener) }
    }
    return reduced
}
