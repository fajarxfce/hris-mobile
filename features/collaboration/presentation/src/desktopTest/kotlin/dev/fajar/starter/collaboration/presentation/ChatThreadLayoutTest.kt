package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ChatThreadLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowLargeTextKeepsComposerAndBackReachableWhileMessagesScroll() {
        val events = mutableListOf<ChatThreadEvent>()
        var state by
            mutableStateOf(
                ChatThreadState(
                    title = "Dewi Kartika",
                    rows = rows(),
                    loading = false,
                    draft = "Baik, terima kasih.",
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatThreadPage(state, rememberLazyListState(), { events += it })
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali")
        val bounds =
            back
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Kirim pesan")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        screenshot("chat-thread-indonesian-large-text")
        compose.onNodeWithText("Pesan lama untuk pengujian.").performScrollTo().assertIsDisplayed()
        assertEquals(bounds, back.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Kirim pesan").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.SendRequested), events)
            state = state.copy(submissionPending = true)
        }
        compose.onNodeWithContentDescription("Pesan").assertIsNotEnabled()
    }

    @Test
    fun composerForwardsExactTextAndUsesSendActionWithoutMovingFocus() {
        val events = mutableListOf<ChatThreadEvent>()
        var state by
            mutableStateOf(
                ChatThreadState(
                    title = "Ari Pratama",
                    loading = false,
                    connection = ChatThreadConnection.CONNECTED,
                    rows = rows(),
                )
            )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme {
                    Box(Modifier.size(390.dp, 780.dp).testTag("frame")) {
                        ChatThreadPage(
                            state,
                            rememberLazyListState(),
                            { event ->
                                events += event
                                if (event is ChatThreadEvent.DraftChanged)
                                    state = state.copy(draft = event.text)
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Message").performTextInput("  review\nplease  ")
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        screenshot("chat-thread-english")
        compose.runOnIdle {
            assertEquals("  review\nplease  ", state.draft)
            assertEquals(ChatThreadEvent.SendRequested, events.last())
        }
    }

    @Test
    fun deletedAndUndecryptableRowsNeverRenderTheirRawBody() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme {
                    ChatThreadPage(
                        ChatThreadState(
                            title = "Ari",
                            loading = false,
                            rows =
                                listOf(
                                    row(
                                            "deleted",
                                            "private old text",
                                            ChatMessageOwnership.COLLEAGUE,
                                        )
                                        .copy(kind = CollaborationMessageKind.REDACTED),
                                    row(
                                            "encrypted",
                                            "opaque ciphertext",
                                            ChatMessageOwnership.UNKNOWN,
                                        )
                                        .copy(kind = CollaborationMessageKind.UNABLE_TO_DECRYPT),
                                ),
                        ),
                        rememberLazyListState(),
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("private old text").assertDoesNotExist()
        compose.onNodeWithText("opaque ciphertext").assertDoesNotExist()
        compose.onNodeWithText("Message deleted").assertIsDisplayed()
        compose.onNodeWithText("This message cannot be decrypted yet.").assertIsDisplayed()
    }

    private fun rows() =
        listOf(
            row("latest", "Baik, dokumen klaimnya sudah saya periksa.", ChatMessageOwnership.SELF),
            row("peer", "Bisa cek lampiran reimbursement saya?", ChatMessageOwnership.COLLEAGUE),
            row("old", "Pesan lama untuk pengujian.", ChatMessageOwnership.COLLEAGUE),
        )

    private fun row(id: String, text: String, own: ChatMessageOwnership) =
        ChatMessageRow(
            id,
            id,
            null,
            1_793_491_200_000,
            own,
            CollaborationMessageKind.TEXT,
            text,
            CollaborationMessageDelivery.SENT,
        )

    private fun screenshot(name: String) {
        Image.makeFromBitmap(compose.onNodeWithTag("frame").captureToImage().asSkiaBitmap()).use {
            image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/$name.png").writeBytes(data.bytes)
            }
        }
    }
}
