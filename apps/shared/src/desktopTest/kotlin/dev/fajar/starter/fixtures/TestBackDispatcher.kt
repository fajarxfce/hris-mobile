@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.fajar.starter.fixtures

import androidx.compose.ui.backhandler.BackGestureDispatcher

/** Drives the same callbacks as a platform gesture, including cancellation and fallback. */
class TestBackDispatcher : BackGestureDispatcher() {
    var unhandled = 0
        private set

    val canHandleBack: Boolean
        get() = activeListener?.active == true

    fun pressBack() {
        val listener = activeListener?.takeIf { it.active }
        if (listener == null) {
            unhandled++
            return
        }
        listener.onStarted()
        listener.onCompleted()
    }

    fun cancelBack() {
        val listener = checkNotNull(activeListener?.takeIf { it.active })
        listener.onStarted()
        listener.onCancelled()
    }
}
