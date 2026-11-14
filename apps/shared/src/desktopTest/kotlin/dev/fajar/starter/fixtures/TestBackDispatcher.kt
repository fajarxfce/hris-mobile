package dev.fajar.starter.fixtures

import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput

/** Drives the same callbacks as a platform gesture, including cancellation and fallback. */
class TestBackDispatcher : NavigationEventDispatcherOwner, AutoCloseable {
    var unhandled = 0
        private set

    var canHandleBack = false
        private set

    override val navigationEventDispatcher = NavigationEventDispatcher { unhandled++ }

    private val input =
        object : NavigationEventInput() {
            override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
                canHandleBack = hasEnabledHandlers
            }

            fun pressBack() {
                dispatchOnBackStarted(NavigationEvent())
                dispatchOnBackCompleted()
            }

            fun cancelBack() {
                check(canHandleBack)
                dispatchOnBackStarted(NavigationEvent())
                dispatchOnBackCancelled()
            }
        }

    init {
        navigationEventDispatcher.addInput(input)
    }

    fun pressBack() = input.pressBack()

    fun cancelBack() = input.cancelBack()

    override fun close() = navigationEventDispatcher.dispose()
}
