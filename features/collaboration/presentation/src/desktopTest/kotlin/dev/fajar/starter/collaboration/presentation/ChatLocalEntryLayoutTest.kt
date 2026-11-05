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
import dev.fajar.starter.collaboration.presentation.replies.ChatUnsentReply
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image

@OptIn(ExperimentalFluentApi::class)
class ChatLocalEntryLayoutTest {
    @Test fun offlineMessagesAndComposerRemainAvailableWithoutGenericRetryUi() = thread(false)

    @Test fun heldReplyRemainsReadableAndActionableAtLargeTextInDarkMode() = thread(true)

    private fun thread(dark: Boolean) =
        runDesktopComposeUiTest(width = 375, height = 780) {
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
                            Box(Modifier.fillMaxSize().testTag("local-chat")) {
                                ChatThreadPage(
                                    ChatThreadState(
                                        title = "Bima Santoso",
                                        loading = false,
                                        draftReady = true,
                                        rows =
                                            listOf(
                                                ChatMessageRow(
                                                    "one",
                                                    "\$one",
                                                    null,
                                                    1_793_865_600_000,
                                                    ChatMessageOwnership.COLLEAGUE,
                                                    CollaborationMessageKind.TEXT,
                                                    "Bisa cek klaim transport saya?",
                                                    CollaborationMessageDelivery.SENT,
                                                )
                                            ),
                                        unsentReplies =
                                            listOf(
                                                ChatUnsentReply(
                                                    "held",
                                                    "Bisa, saya cek setelah rapat.",
                                                )
                                            ),
                                        failure =
                                            Failure(
                                                FailureKind.Unexpected,
                                                "Something went wrong. Try again.",
                                            ),
                                    ),
                                    rememberLazyListState(),
                                    events::add,
                                )
                            }
                        }
                    }
                }
            }
            onNodeWithText("Bima Santoso").assertIsDisplayed()
            onNodeWithText("Menunggu koneksi").assertDoesNotExist()
            onNodeWithContentDescription("Muat ulang").assertDoesNotExist()
            onNodeWithText("Something went wrong. Try again.").assertDoesNotExist()
            onNodeWithText("Coba lagi").assertDoesNotExist()
            onNodeWithText("Bisa cek klaim transport saya?").assertIsDisplayed()
            onNodeWithText("Belum terkirim · Ketuk untuk periksa").assertIsDisplayed()
            onNodeWithTag("chat-unsent-held").assertHeightIsAtLeast(48.dp).performClick()
            runOnIdle {
                assertEquals(
                    listOf<ChatThreadEvent>(ChatThreadEvent.UnsentReplySelected("held")),
                    events,
                )
            }
            Image.makeFromBitmap(onNodeWithTag("local-chat").captureToImage().asSkiaBitmap()).use {
                image ->
                checkNotNull(image.encodeToData()).use { data ->
                    File("build/reports/screenshots").mkdirs()
                    File(
                            "build/reports/screenshots/chat-local-${if (dark) "dark-large" else "light"}.png"
                        )
                        .writeBytes(data.bytes)
                }
            }
        }

    @Test
    fun emptyLocalCacheDoesNotClaimTheConversationHasNoHistoryWhileOffline() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            var state by
                mutableStateOf(
                    ChatThreadState(
                        title = "Bima Santoso",
                        loading = false,
                        draftReady = true,
                        failure = Failure(FailureKind.Network, "Offline"),
                    )
                )
            setContent { AppTheme { ChatThreadPage(state, rememberLazyListState(), {}) } }
            onNodeWithText("Write your first message.").assertDoesNotExist()
            onNodeWithText("Waiting for connection").assertDoesNotExist()
            runOnIdle { state = state.copy(connection = ChatThreadConnection.CONNECTED) }
            onNodeWithText("Write your first message.").assertDoesNotExist()
            runOnIdle { state = state.copy(failure = null, syncing = true) }
            onNodeWithText("Write your first message.").assertDoesNotExist()
            runOnIdle { state = state.copy(syncing = false) }
            onNodeWithText("Write your first message.").assertIsDisplayed()
        }

    @Test
    fun emptyConversationKeepsTypingAvailableWithoutManualNetworkControls() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            var state by
                mutableStateOf(
                    ChatThreadState(
                        title = "Bima Santoso",
                        loading = false,
                        draftReady = true,
                        hasOlder = true,
                        connection = ChatThreadConnection.CONNECTED,
                    )
                )
            val events = mutableListOf<ChatThreadEvent>()
            setContent { AppTheme { ChatThreadPage(state, rememberLazyListState(), events::add) } }
            onNodeWithText("Messages will appear here.").assertIsDisplayed()
            onNodeWithText("Write your first message.").assertDoesNotExist()
            onNodeWithText("Load messages").assertDoesNotExist()
            onNode(hasSetTextAction()).performTextInput("Hello")
            runOnIdle {
                assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.DraftChanged("Hello")), events)
                state = state.copy(loadingOlder = true, syncing = true)
            }
            onNodeWithText("Load messages").assertDoesNotExist()
            onNode(
                    SemanticsMatcher.keyIsDefined(
                        androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
                    )
                )
                .assertDoesNotExist()
            runOnIdle {
                state =
                    state.copy(
                        loadingOlder = false,
                        syncing = false,
                        connection = ChatThreadConnection.OFFLINE,
                        failure = Failure(FailureKind.Network, "Offline"),
                    )
            }
            onNodeWithText("Load messages").assertDoesNotExist()
            onNode(hasSetTextAction()).assertIsEnabled()
            onNodeWithText("Write your first message.").assertDoesNotExist()
        }

    @Test
    fun openingLocalStorageDoesNotShowANetworkLoadingSpinner() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            setContent {
                AppTheme { ChatThreadPage(ChatThreadState(), rememberLazyListState(), {}) }
            }
            onNodeWithText("Load messages").assertDoesNotExist()
            onNodeWithContentDescription("Refresh").assertDoesNotExist()
        }
}
