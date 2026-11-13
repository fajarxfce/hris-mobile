@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.navigation.SpaceChatThreadViewport
import dev.fajar.starter.collaboration.presentation.spacethread.*
import dev.fajar.starter.collaboration.presentation.spacethread.pages.SpaceChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.skia.Image

@OptIn(ExperimentalFluentApi::class)
class SpaceChatPageLayoutTest {
    @Test
    fun compactRoomShowsMessagesWithLongPressActionsAndNoPermanentSelectionButton() =
        selection(false, false)

    @Test fun darkLargeTextKeepsMessageAndSelectionActionsReachable() = selection(true, false)

    @Test fun landscapeKeepsComposerAndSelectionWithinTheAvailableHeight() = selection(false, true)

    private fun selection(dark: Boolean, landscape: Boolean) =
        runDesktopComposeUiTest(
            width = if (landscape) 720 else 375,
            height = if (landscape) 375 else 780,
        ) {
            val f = SpaceChatRowsFixture()
            val thread =
                f.thread(
                    listOf(f.history(f.sent(2)).copy(text = "Bisa, saya cek setelah rapat.")),
                    commands = listOf(f.record()),
                )
            var state by
                mutableStateOf(
                    SpaceChatThreadState(
                        thread = thread,
                        rows = f.rows(thread),
                        loading = false,
                        draftReady = true,
                    )
                )
            val events = mutableListOf<SpaceChatThreadEvent>()
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (dark) 2f else 1f),
                    LocalAppLanguage provides AppLanguage.English,
                ) {
                    AppTheme(reduceMotion = true) {
                        FluentTheme(
                            colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = dark)
                        ) {
                            Box(Modifier.fillMaxSize().testTag("group-frame")) {
                                SpaceChatThreadPage(
                                    state,
                                    rememberLazyListState(),
                                    { event ->
                                        events += event
                                        when (event) {
                                            is SpaceChatThreadEvent.MessageSelected ->
                                                state =
                                                    state.copy(
                                                        selection = state.selection + event.identity
                                                    )
                                            SpaceChatThreadEvent.SelectionCleared ->
                                                state = state.copy(selection = emptySet())
                                            else -> Unit
                                        }
                                    },
                                    today = LocalDate(2026, 11, 13),
                                    timeZone = TimeZone.UTC,
                                )
                            }
                        }
                    }
                }
            }
            onNodeWithContentDescription("Copy").assertDoesNotExist()
            onNodeWithText("Connected").assertDoesNotExist()
            onNodeWithText("Older messages").assertDoesNotExist()
            onNodeWithText("Original 1").assertIsDisplayed().performTouchInput { longClick() }
            onNodeWithContentDescription("Copy").assertIsDisplayed().assertIsEnabled()
            onNodeWithText("1 selected").assertIsDisplayed()
            onNodeWithText("Bisa, saya cek setelah rapat.").performClick()
            onNodeWithText("2 selected").assertIsDisplayed()
            assertEquals(
                2,
                events
                    .filterIsInstance<SpaceChatThreadEvent.MessageSelected>()
                    .map { it.identity }
                    .distinct()
                    .size,
            )
            onNodeWithContentDescription("Copy").performClick()
            assertTrue(SpaceChatThreadEvent.CopySelected in events)
            val name = if (landscape) "landscape" else if (dark) "dark-large" else "compact"
            Image.makeFromBitmap(onNodeWithTag("group-frame").captureToImage().asSkiaBitmap())
                .use { image ->
                    checkNotNull(image.encodeToData()).use { data ->
                        File("build/reports/screenshots").mkdirs()
                        File("build/reports/screenshots/group-chat-selection-$name.png")
                            .writeBytes(data.bytes)
                    }
                }
        }

    @Test
    fun newestGestureAppearsAboveTheComposerInItsFirstConstrainedLayout() =
        runDesktopComposeUiTest(width = 375, height = 480) {
            val f = SpaceChatRowsFixture()
            val history = (1..20).map { f.history(f.sent(it)).copy(sender = "@peer:example.test") }
            val thread = f.thread(history)
            var state by
                mutableStateOf(
                    SpaceChatThreadState(
                        thread = thread,
                        rows = f.rows(thread),
                        loading = false,
                        draftReady = true,
                    )
                )
            lateinit var list: LazyListState
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        list = rememberLazyListState()
                        SpaceChatThreadViewport(state, list)
                        SpaceChatThreadPage(
                            state,
                            list,
                            {},
                            timeZone = TimeZone.UTC,
                            today = LocalDate(2026, 11, 13),
                        )
                    }
                }
            }
            mainClock.autoAdvance = false
            runOnUiThread { state = state.copy(rows = f.rows(thread, f.composer(21), state.rows)) }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("Original 21").assertIsDisplayed()
            val message = onNodeWithText("Original 21").getUnclippedBoundsInRoot()
            val composer = onNodeWithContentDescription("Message").getUnclippedBoundsInRoot()
            assertTrue(message.bottom <= composer.top)
            runOnIdle { assertEquals(0, list.firstVisibleItemIndex) }
        }

    @Test
    fun aNewMessageKeepsAnOlderViewportOnItsCurrentAnchor() =
        runDesktopComposeUiTest(width = 375, height = 480) {
            val f = SpaceChatRowsFixture()
            val thread = f.thread((1..25).map { f.history(f.sent(it)) })
            var state by
                mutableStateOf(
                    SpaceChatThreadState(thread = thread, rows = f.rows(thread), loading = false)
                )
            lateinit var list: LazyListState
            setContent {
                AppTheme(reduceMotion = true) {
                    list = rememberLazyListState()
                    SpaceChatThreadViewport(state, list)
                    SpaceChatThreadPage(state, list, {}, timeZone = TimeZone.UTC)
                }
            }
            runOnIdle { list.requestScrollToItem(10) }
            var anchor: Any? = null
            runOnIdle { anchor = list.layoutInfo.visibleItemsInfo.first().key }
            runOnUiThread { state = state.copy(rows = f.rows(thread, f.composer(26), state.rows)) }
            waitForIdle()
            runOnIdle { assertEquals(anchor, list.layoutInfo.visibleItemsInfo.first().key) }
        }

    @Test
    fun alreadyReadRowsRemainVisibleWhileTheIndependentHistoryReadIsPending() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val f = SpaceChatRowsFixture()
            val state =
                SpaceChatThreadState(
                    loading = true,
                    rows = f.rows(composer = f.composer(1)),
                    draftReady = true,
                    draft = "Continue typing",
                )
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        SpaceChatThreadPage(state, rememberLazyListState(), {})
                    }
                }
            }
            onNodeWithText("Original 1").assertIsDisplayed()
            onNodeWithContentDescription("Message")
                .assertIsEnabled()
                .assertTextEquals("Continue typing")
            onNodeWithContentDescription("Send message").assertIsEnabled()
        }
}
