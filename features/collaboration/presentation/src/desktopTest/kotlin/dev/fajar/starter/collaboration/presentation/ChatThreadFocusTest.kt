package dev.fajar.starter.collaboration.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.presentation.navigation.ChatThreadFocusObserver
import dev.fajar.starter.collaboration.presentation.thread.*
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class ChatThreadFocusTest {
    @get:Rule val compose = createComposeRule()
    private val owner =
        CollaborationOwner("login", "https://hris.example.test", "account", "company", "native")

    @Test
    fun routeRegistrationFollowsLifecycleWindowDialogsOwnerAndDisposal() {
        val lifecycle = FocusLifecycle()
        lifecycle.registry.currentState = Lifecycle.State.STARTED
        val window = FocusWindow()
        var state by mutableStateOf(ChatThreadState(notificationOwner = owner))
        var visible by mutableStateOf(true)
        val active = mutableListOf<CollaborationOwner>()
        var opened = 0
        var closed = 0
        val register: (CollaborationOwner, String) -> AutoCloseable? = { captured, conversation ->
            assertEquals("conversation", conversation)
            assertTrue(active.isEmpty(), "Previous route must release before the next registration")
            active += captured
            opened++
            AutoCloseable {
                assertTrue(active.remove(captured))
                closed++
            }
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycle,
                LocalWindowInfo provides window,
            ) {
                if (visible) ChatThreadFocusObserver(state, "conversation", register)
            }
        }
        compose.runOnIdle {
            assertEquals(0, opened)
            lifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertEquals(listOf(owner), active)
            state = state.copy(draft = "typing")
        }
        compose.runOnIdle {
            assertEquals(1, opened)
            window.focused = false
        }
        compose.runOnIdle {
            assertTrue(active.isEmpty())
            window.focused = true
        }
        compose.runOnIdle {
            assertEquals(listOf(owner), active)
            state = state.copy(draftDialog = ChatDraftDialog.LEAVE)
        }
        compose.runOnIdle {
            assertTrue(active.isEmpty())
            state = state.copy(draftDialog = null)
        }
        compose.runOnIdle {
            assertEquals(listOf(owner), active)
            lifecycle.registry.currentState = Lifecycle.State.STARTED
        }
        compose.runOnIdle {
            assertTrue(active.isEmpty())
            lifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        val next = owner.copy(nativeSessionId = "replacement-native")
        compose.runOnIdle {
            assertEquals(listOf(owner), active)
            state = state.copy(notificationOwner = next)
        }
        compose.runOnIdle {
            assertEquals(listOf(next), active)
            state = state.copy(leaving = true)
        }
        compose.runOnIdle {
            assertTrue(active.isEmpty())
            state = state.copy(leaving = false)
        }
        compose.runOnIdle {
            assertEquals(listOf(next), active)
            visible = false
        }
        compose.runOnIdle {
            assertTrue(active.isEmpty())
            assertEquals(opened, closed)
        }
    }

    @Test
    fun missingOwnedContextCannotRegisterAndCallbackReplacementReleasesItsOriginal() {
        val lifecycle = FocusLifecycle()
        lifecycle.registry.currentState = Lifecycle.State.RESUMED
        val window = FocusWindow()
        var state by mutableStateOf(ChatThreadState())
        val events = mutableListOf<String>()
        var register by
            mutableStateOf<(CollaborationOwner, String) -> AutoCloseable?>({ _, _ ->
                events += "first-open"
                AutoCloseable { events += "first-close" }
            })
        compose.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycle,
                LocalWindowInfo provides window,
            ) {
                ChatThreadFocusObserver(state, "untrusted-route-id", register)
            }
        }
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            state = state.copy(notificationOwner = owner)
        }
        compose.runOnIdle {
            assertEquals(listOf("first-open"), events)
            register = { _, _ ->
                events += "second-open"
                AutoCloseable { events += "second-close" }
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("first-open", "first-close", "second-open"), events)
            state = state.copy(notificationOwner = null)
        }
        compose.runOnIdle { assertEquals("second-close", events.last()) }
    }
}

private class FocusLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle
        get() = registry
}

private class FocusWindow : WindowInfo {
    var focused by mutableStateOf(true)
    override val isWindowFocused: Boolean
        get() = focused
}
