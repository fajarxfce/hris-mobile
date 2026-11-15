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
import dev.fajar.starter.collaboration.presentation.navigation.ChatMessageEndViewport
import dev.fajar.starter.collaboration.presentation.recipient.*
import dev.fajar.starter.collaboration.presentation.recipient.pages.RecipientChatThreadPage
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
class RecipientChatPageLayoutTest {
    @Test fun compactRoomSelectsMessagesThroughLongPress() = selection(false, false)

    @Test fun darkLargeTextKeepsActionsReachable() = selection(true, false)

    @Test fun landscapeKeepsSelectionAndComposerWithinTheWindow() = selection(false, true)

    @Test
    fun largePhoneKeepsMessagesAndSelectionReachable() =
        selection(false, false, width = 430, height = 932, name = "large-phone")

    @Test
    fun tabletCentersTheConversationWithoutStretchingTheReadingColumn() =
        selection(false, true, width = 1024, height = 768, name = "tablet")

    private fun selection(
        dark: Boolean,
        landscape: Boolean,
        width: Int = if (landscape) 720 else 375,
        height: Int = if (landscape) 375 else 780,
        name: String = if (landscape) "landscape" else if (dark) "dark-large" else "compact",
    ) =
        runDesktopComposeUiTest(width = width, height = height) {
            var state by mutableStateOf(initial(2))
            val events = mutableListOf<RecipientChatThreadEvent>()
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
                            RecipientChatThreadPage(
                                state,
                                rememberLazyListState(),
                                { event ->
                                    events += event
                                    state =
                                        when (event) {
                                            is RecipientChatThreadEvent.MessageSelected ->
                                                state.copy(
                                                    selection = state.selection + event.identity
                                                )
                                            RecipientChatThreadEvent.SelectionCleared ->
                                                state.copy(selection = emptySet())
                                            else -> state
                                        }
                                },
                                Modifier.fillMaxSize().testTag("recipient-frame"),
                                peerName = "Bima Santoso",
                                today = LocalDate(2026, 11, 14),
                                timeZone = TimeZone.UTC,
                            )
                        }
                    }
                }
            }
            onNodeWithText("Bima Santoso").assertIsDisplayed()
            onNodeWithContentDescription("Copy").assertDoesNotExist()
            onNodeWithText("Connected").assertDoesNotExist()
            onNodeWithText("Older messages").assertDoesNotExist()
            onNodeWithText("Recipient 1").assertIsDisplayed().performTouchInput { longClick() }
            onNodeWithText("1 selected").assertIsDisplayed()
            onNodeWithContentDescription("Copy").assertIsDisplayed().assertIsEnabled()
            onNodeWithText("Recipient 2").performClick()
            onNodeWithText("2 selected").assertIsDisplayed()
            onNodeWithContentDescription("Copy").performClick()
            runOnIdle {
                assertEquals(
                    2,
                    events
                        .filterIsInstance<RecipientChatThreadEvent.MessageSelected>()
                        .map { it.identity }
                        .distinct()
                        .size,
                )
                assertTrue(RecipientChatThreadEvent.CopySelected in events)
            }
            if (width > 720) {
                val frame = onNodeWithTag("recipient-frame").getUnclippedBoundsInRoot()
                val header = onNodeWithTag("app-top-bar").getUnclippedBoundsInRoot()
                assertTrue(header.left > frame.left && header.right < frame.right)
                assertEquals(header.left - frame.left, frame.right - header.right)
            }
            Image.makeFromBitmap(onNodeWithTag("recipient-frame").captureToImage().asSkiaBitmap())
                .use { image ->
                    checkNotNull(image.encodeToData()).use { data ->
                        val file =
                            File("build/reports/screenshots/recipient-chat-selection-$name.png")
                        file.parentFile.mkdirs()
                        file.writeBytes(data.bytes)
                    }
                }
        }

    @Test
    fun retainedRowsAndEditorStayVisibleWhileALocalReadIsPending() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val state =
                initial(1)
                    .copy(
                        loading = true,
                        editor =
                            RecipientComposerEditingState(
                                ready = true,
                                text = "Continue typing",
                                saving = true,
                            ),
                    )
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        RecipientChatThreadPage(state, rememberLazyListState(), {})
                    }
                }
            }
            onNodeWithText("Recipient 1").assertIsDisplayed()
            onNodeWithContentDescription("Message")
                .assertIsEnabled()
                .assertTextEquals("Continue typing")
            onNodeWithContentDescription("Send message").assertIsEnabled()
        }

    @Test
    fun newestMessageIsAboveTheComposerInItsFirstConstrainedFrame() =
        runDesktopComposeUiTest(width = 375, height = 480) {
            var state by mutableStateOf(initial(20))
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        val list = rememberLazyListState()
                        ChatMessageEndViewport(
                            state.rows.firstOrNull()?.identity?.stableKey,
                            true,
                            list,
                        )
                        RecipientChatThreadPage(state, list, {}, timeZone = TimeZone.UTC)
                    }
                }
            }
            mainClock.autoAdvance = false
            runOnUiThread { state = initial(21, state.rows) }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("Recipient 21").assertIsDisplayed()
            val message = onNodeWithText("Recipient 21").getUnclippedBoundsInRoot()
            val composer = onNodeWithContentDescription("Message").getUnclippedBoundsInRoot()
            assertTrue(message.bottom <= composer.top, "$message overlaps $composer")
        }

    @Test
    fun newMessagePreservesAnOlderReadingAnchor() =
        runDesktopComposeUiTest(width = 375, height = 480) {
            var state by mutableStateOf(initial(25))
            lateinit var list: LazyListState
            setContent {
                AppTheme(reduceMotion = true) {
                    list = rememberLazyListState()
                    ChatMessageEndViewport(
                        state.rows.firstOrNull()?.identity?.stableKey,
                        true,
                        list,
                    )
                    RecipientChatThreadPage(state, list, {}, timeZone = TimeZone.UTC)
                }
            }
            runOnIdle { list.requestScrollToItem(10) }
            var anchor: Any? = null
            runOnIdle { anchor = list.layoutInfo.visibleItemsInfo.first().key }
            runOnUiThread { state = initial(26, state.rows) }
            waitForIdle()
            runOnIdle { assertEquals(anchor, list.layoutInfo.visibleItemsInfo.first().key) }
        }

    @Test
    fun sendingDuringLocalPersistenceKeepsTheInputFocusedAndAcceptsLaterTyping() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            var state by
                mutableStateOf(
                    initial(1)
                        .copy(editor = RecipientComposerEditingState(ready = true, saving = true))
                )
            var sends = 0
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        RecipientChatThreadPage(
                            state,
                            rememberLazyListState(),
                            { event ->
                                when (event) {
                                    is RecipientChatThreadEvent.TextChanged ->
                                        state =
                                            state.copy(
                                                editor = state.editor.copy(text = event.text)
                                            )
                                    RecipientChatThreadEvent.SendRequested -> {
                                        sends++
                                        state = state.copy(editor = state.editor.copy(text = ""))
                                    }
                                    else -> Unit
                                }
                            },
                        )
                    }
                }
            }
            onNodeWithContentDescription("Message").performTextInput("First")
            onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
            onNodeWithContentDescription("Message").assertIsFocused().performTextInput("Later")
            onNodeWithContentDescription("Send message").assertIsEnabled()
            runOnIdle {
                assertEquals(1, sends)
                assertEquals("Later", state.editor.text)
            }
        }

    private fun initial(
        count: Int,
        previous: List<RecipientChatMessageRow> = emptyList(),
    ): RecipientChatThreadState {
        val f = RecipientChatRowsFixture()
        val messages =
            f.facts.success(
                f.facts.read(conversation = null, source = (1..count).map { f.facts.original(it) })
            )
        return RecipientChatThreadState(
            visible = true,
            loading = false,
            editor = RecipientComposerEditingState(ready = true),
            messages = messages,
            rows = f.rows(messages, conversation = null, previous = previous),
        )
    }
}
