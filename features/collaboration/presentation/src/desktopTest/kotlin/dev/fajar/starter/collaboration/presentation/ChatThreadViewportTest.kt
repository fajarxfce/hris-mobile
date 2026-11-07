package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.navigation.ChatThreadViewport
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class ChatThreadViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun resumedFocusedPortraitViewportTracksScrollAndStopsBehindDialogs() =
        checkViewport(375.dp, 720.dp, 2f)

    @Test
    fun landscapeViewportReacquiresVisibleRowsAfterPauseAndDisposalClearsIt() =
        checkViewport(720.dp, 375.dp, 1f)

    private fun checkViewport(width: Dp, height: Dp, fontScale: Float) {
        val owner = ViewportLifecycle()
        owner.registry.currentState = Lifecycle.State.STARTED
        val window = ViewportWindow()
        val events = mutableListOf<ChatThreadEvent.ViewportChanged>()
        var render by mutableStateOf(true)
        var state by
            mutableStateOf(
                ChatThreadState(
                    title = "Fictional colleague",
                    readViewportToken = "owned-view",
                    loading = false,
                    rows =
                        List(40) { index ->
                            ChatMessageRow(
                                "row-$index",
                                "\$event-$index",
                                null,
                                index.toLong(),
                                ChatMessageOwnership.COLLEAGUE,
                                CollaborationMessageKind.TEXT,
                                "Fictional message $index, retained locally for this viewport test.",
                                CollaborationMessageDelivery.SENT,
                            )
                        },
                )
            )
        lateinit var list: LazyListState
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalWindowInfo provides window,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                AppTheme {
                    if (render) {
                        list = rememberLazyListState()
                        Box(Modifier.size(width, height)) {
                            ChatThreadViewport(state, list) {
                                events += assertIs<ChatThreadEvent.ViewportChanged>(it)
                            }
                            ChatThreadPage(state, list, {})
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertEquals("owned-view", events.last().token)
            assertTrue("row-0" in events.last().rowKeys)
            assertTrue(events.last().rowKeys.size in 1..15)
            assertFalse("row-39" in events.last().rowKeys)
            window.focused = false
        }
        compose.runOnIdle {
            assertTrue(events.last().rowKeys.isEmpty())
            list.requestScrollToItem(20)
        }
        compose.runOnIdle {
            assertTrue(events.last().rowKeys.isEmpty())
            window.focused = true
        }
        compose.runOnIdle {
            assertTrue("row-20" in events.last().rowKeys)
            assertFalse("row-0" in events.last().rowKeys)
            state = state.copy(draftDialog = ChatDraftDialog.LEAVE)
        }
        compose.runOnIdle {
            assertTrue(events.last().rowKeys.isEmpty())
            state = state.copy(draftDialog = null)
        }
        compose.runOnIdle {
            assertTrue("row-20" in events.last().rowKeys)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        compose.runOnIdle {
            assertTrue(events.last().rowKeys.isEmpty())
            list.requestScrollToItem(30)
        }
        compose.runOnIdle {
            assertTrue(events.last().rowKeys.isEmpty())
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertTrue("row-30" in events.last().rowKeys)
            render = false
        }
        compose.runOnIdle { assertTrue(events.last().rowKeys.isEmpty()) }
    }
}

private class ViewportLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle
        get() = registry
}

private class ViewportWindow : WindowInfo {
    var focused by mutableStateOf(true)
    override val isWindowFocused: Boolean
        get() = focused
}
