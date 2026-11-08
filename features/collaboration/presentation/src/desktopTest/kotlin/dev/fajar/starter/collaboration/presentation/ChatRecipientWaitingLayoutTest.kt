@file:OptIn(io.github.composefluent.ExperimentalFluentApi::class)

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
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ChatRecipientWaitingLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun oneWaitingExplanationKeepsComposerAndOriginalMessagesAvailable() = exercise(false, false)

    @Test fun largeDarkTextFitsWithoutCoveringInput() = exercise(true, false)

    @Test fun landscapeWaitingExplanationStaysInsideHistory() = exercise(false, true)

    private fun exercise(dark: Boolean, landscape: Boolean) {
        val pending =
            ChatMessageRow(
                "original",
                null,
                "original",
                1_793_491_200_000,
                ChatMessageOwnership.SELF,
                CollaborationMessageKind.TEXT,
                "Tolong cek klaim ini.",
                CollaborationMessageDelivery.PENDING,
                canCancel = true,
            )
        var state by
            mutableStateOf(
                ChatThreadState(
                    title = "Dewi Kartika",
                    rows = listOf(pending),
                    loading = false,
                    draftReady = true,
                    recipientUnavailable = false,
                )
            )
        val events = mutableListOf<ChatThreadEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (dark) 2f else 1f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme(reduceMotion = true) {
                    FluentTheme(
                        colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = dark)
                    ) {
                        Box(
                            Modifier.size(
                                    if (landscape) 720.dp else 375.dp,
                                    if (landscape) 375.dp else 780.dp,
                                )
                                .testTag("frame")
                        ) {
                            ChatThreadPage(state, rememberLazyListState(), events::add)
                        }
                    }
                }
            }
        }
        val notice = "Penerima belum bisa menerima pesan."
        compose.onNodeWithText("Tolong cek klaim ini.").assertIsDisplayed()
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(recipientUnavailable = true) }
        compose.onAllNodesWithText(notice).assertCountEquals(1)
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithText("Tolong cek klaim ini.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pesan").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Coba lagi").assertDoesNotExist()
        val description = compose.onNodeWithText(notice).fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithContentDescription("Pesan").fetchSemanticsNode().boundsInRoot
        assertTrue(description.bottom <= input.top)
        val suffix = if (landscape) "landscape" else if (dark) "dark-large-text" else "compact"
        screenshot("chat-recipient-waiting-$suffix")
        compose.runOnIdle {
            state =
                state.copy(
                    rows = listOf(pending.copy(delivery = CollaborationMessageDelivery.SENT))
                )
        }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithText("Tolong cek klaim ini.").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(rows = emptyList()) }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(rows = listOf(pending), recipientUnavailable = false)
        }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithContentDescription("Pesan").assertIsEnabled()
        assertTrue(events.isEmpty())
    }

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
