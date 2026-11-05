@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.Image

@OptIn(ExperimentalFluentApi::class)
class ChatQuotedReplyLayoutTest {
    @Test fun compactReplyAndClearKeepTheComposerReachable() = layout(false, false)

    @Test fun largeTextReplyAndClearKeepTheComposerReachable() = layout(true, false)

    @Test fun landscapeReplyAndClearKeepTheComposerReachable() = layout(false, true)

    private fun layout(dark: Boolean, landscape: Boolean) =
        runDesktopComposeUiTest(
            width = if (landscape) 760 else 375,
            height = if (landscape) 375 else 780,
        ) {
            val original =
                row("one", "Bisa cek klaim transport saya untuk kunjungan kemarin?", false)
            val answer =
                row("two", "Bisa, saya cek setelah rapat.", true)
                    .copy(replyToEventId = original.eventId)
            var state by
                mutableStateOf(
                    ChatThreadState(
                        title = "Nadia Pratama",
                        loading = false,
                        draftReady = true,
                        draft = "Siap, saya periksa.",
                        rows = listOf(answer, original),
                        selfSender = "@self:example.test",
                    )
                )
            val events = mutableListOf<ChatThreadEvent>()
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (dark) 2f else 1f),
                    LocalAppLanguage provides AppLanguage.Indonesian,
                ) {
                    AppTheme(reduceMotion = true) {
                        FluentTheme(
                            colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = dark)
                        ) {
                            Box(Modifier.fillMaxSize().testTag("reply-frame")) {
                                ChatThreadPage(
                                    state,
                                    rememberLazyListState(),
                                    { event ->
                                        events += event
                                        state =
                                            when (event) {
                                                is ChatThreadEvent.MessageSelected ->
                                                    state.copy(
                                                        selecting = true,
                                                        selectedMessages =
                                                            state.selectedMessages +
                                                                state.rows.single {
                                                                    it.key == event.key
                                                                },
                                                    )
                                                ChatThreadEvent.ReplySelected ->
                                                    state.copy(
                                                        selecting = false,
                                                        draftReplyToEventId =
                                                            state.selectedMessages.single().eventId,
                                                        selectedMessages = emptyList(),
                                                    )
                                                ChatThreadEvent.ReplyCleared ->
                                                    state.copy(draftReplyToEventId = null)
                                                is ChatThreadEvent.DraftChanged ->
                                                    state.copy(draft = event.text)
                                                else -> state
                                            }
                                    },
                                    today = LocalDate(2026, 11, 5),
                                )
                            }
                        }
                    }
                }
            }
            onNodeWithContentDescription("Balas").assertDoesNotExist()
            onNodeWithTag("chat-message-one").performTouchInput { longClick() }
            onNodeWithContentDescription("Balas")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            onNodeWithContentDescription("Pesan")
                .assertIsEnabled()
                .assertTextContains("Siap, saya periksa.")
            onNodeWithContentDescription("Batalkan balasan")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            screenshot(
                onNodeWithTag("reply-frame"),
                if (landscape) "landscape" else if (dark) "dark-large-text" else "light",
            )
            onNodeWithContentDescription("Batalkan balasan").performClick()
            onNodeWithContentDescription("Batalkan balasan").assertDoesNotExist()
            onNodeWithContentDescription("Pesan").assertTextContains("Siap, saya periksa.")
            onNodeWithTag("chat-message-one").performTouchInput { longClick() }
            onNodeWithTag("chat-message-two").performClick()
            onNodeWithContentDescription("Balas").assertDoesNotExist()
            runOnIdle {
                assertTrue(
                    events.containsAll(
                        listOf(ChatThreadEvent.ReplySelected, ChatThreadEvent.ReplyCleared)
                    )
                )
            }
        }

    private fun row(id: String, body: String, own: Boolean) =
        ChatMessageRow(
            id,
            "\$$id",
            null,
            1_793_865_600_000,
            if (own) ChatMessageOwnership.SELF else ChatMessageOwnership.COLLEAGUE,
            CollaborationMessageKind.TEXT,
            body,
            CollaborationMessageDelivery.SENT,
            reference =
                CollaborationMessageReference(
                    if (own) "@self:example.test" else "@peer:example.test",
                    "\$$id",
                ),
        )

    private fun screenshot(node: SemanticsNodeInteraction, name: String) {
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/chat-quoted-reply-$name.png").writeBytes(data.bytes)
            }
        }
    }
}
