package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
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
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ChatNewMessagePositionTest {
    @get:Rule val compose = createComposeRule()
    private val owner = NewMessageLifecycle()

    @After
    fun closeOwner() {
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    @Test
    fun newPendingMessageIsAboveTheComposerOnItsFirstFrameAtLargeText() =
        checkFirstFrame(320.dp, 640.dp, 2f, "portrait")

    @Test
    fun newPendingMessageIsAboveTheComposerOnItsFirstLandscapeFrame() =
        checkFirstFrame(720.dp, 375.dp, 1f, "landscape")

    private fun checkFirstFrame(width: Dp, height: Dp, scale: Float, name: String) {
        var state by mutableStateOf(initial())
        lateinit var list: LazyListState
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalDensity provides Density(density.density, scale),
                LocalAppLanguage provides AppLanguage.English,
            ) {
                AppTheme(reduceMotion = true) {
                    list = rememberLazyListState()
                    Box(Modifier.size(width, height).testTag("frame")) {
                        ChatThreadViewport(state, list, {})
                        ChatThreadPage(
                            state,
                            list,
                            {},
                            today = LocalDate(2026, 11, 7),
                            timeZone = TimeZone.UTC,
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state = state.copy(rows = listOf(pending()) + state.rows) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("New pending message").assertIsDisplayed()
        val message = compose.onNodeWithText("New pending message").getUnclippedBoundsInRoot()
        val input = compose.onNodeWithContentDescription("Message").getUnclippedBoundsInRoot()
        assertTrue(message.bottom <= input.top, "First-frame message must be above the composer")
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex) }
        Image.makeFromBitmap(compose.onNodeWithTag("frame").captureToImage().asSkiaBitmap()).use {
            image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/chat-new-pending-first-frame-$name.png")
                    .writeBytes(data.bytes)
            }
        }
    }

    @Test
    fun newMessagesDoNotPullAnOlderViewportAwayFromItsVisibleAnchor() {
        var state by mutableStateOf(initial())
        lateinit var list: LazyListState
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AppTheme(reduceMotion = true) {
                    list = rememberLazyListState()
                    Box(Modifier.size(375.dp, 640.dp)) {
                        ChatThreadViewport(state, list, {})
                        ChatThreadPage(
                            state,
                            list,
                            {},
                            today = LocalDate(2026, 11, 7),
                            timeZone = TimeZone.UTC,
                        )
                    }
                }
            }
        }
        compose.runOnIdle { list.requestScrollToItem(12) }
        var anchor: Any? = null
        compose.runOnIdle {
            assertEquals(12, list.firstVisibleItemIndex)
            anchor = list.layoutInfo.visibleItemsInfo.first().key
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { state = state.copy(rows = listOf(pending()) + state.rows) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor, list.layoutInfo.visibleItemsInfo.first().key)
            assertTrue(list.firstVisibleItemIndex > 0)
        }
    }

    private fun initial() =
        ChatThreadState(
            title = "Fictional colleague",
            draftReady = true,
            loading = false,
            rows =
                List(24) { index ->
                    ChatMessageRow(
                        "previous-$index",
                        "event-$index",
                        null,
                        1_000L + index,
                        ChatMessageOwnership.COLLEAGUE,
                        CollaborationMessageKind.TEXT,
                        "Previously stored message $index",
                        CollaborationMessageDelivery.SENT,
                    )
                },
        )

    private fun pending() =
        ChatMessageRow(
            "outgoing:original",
            null,
            "original",
            2_000L,
            ChatMessageOwnership.SELF,
            CollaborationMessageKind.TEXT,
            "New pending message",
            CollaborationMessageDelivery.PENDING,
        )
}

/** Positioning must be ready during entry, before resumed read tracking starts. */
private class NewMessageLifecycle : LifecycleOwner {
    val registry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
    override val lifecycle: Lifecycle
        get() = registry
}
