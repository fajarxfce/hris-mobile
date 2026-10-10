package dev.fajar.starter.designsystem.platform

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView

/** Composition owns this window flag and restores the host's previous value on disposal. */
@Composable
actual fun AppPrivacyProtection(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val activity =
            generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
        val window = activity?.window
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val previouslySecure = window?.attributes?.flags?.and(flag) != 0
        if (enabled) window?.addFlags(flag)
        onDispose { if (enabled && !previouslySecure) window?.clearFlags(flag) }
    }
}
