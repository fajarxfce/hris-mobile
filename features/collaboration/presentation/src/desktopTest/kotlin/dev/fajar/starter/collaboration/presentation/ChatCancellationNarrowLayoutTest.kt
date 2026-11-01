@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.thread.ChatCancellationDialog
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadEvent
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatCancellationDialog as CancellationDialog
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image

class ChatCancellationNarrowLayoutTest {
    @Test
    fun narrowDialogScrollsLongContentWhileKeepingCloseAndRetryInTheViewport() =
        runDesktopComposeUiTest(width = 320, height = 640) {
            val events = mutableListOf<ChatThreadEvent>()
            val state =
                ChatCancellationDialog(
                    "Cuplikan pesan yang dipilih. ".repeat(150),
                    failure = Failure(FailureKind.Storage, "Unavailable"),
                )
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2f),
                    LocalAppLanguage provides AppLanguage.Indonesian,
                ) {
                    AppTheme { CancellationDialog(state, events::add) }
                }
            }
            val close = onNodeWithContentDescription("Tutup").assertIsDisplayed()
            val beforeClose = close.fetchSemanticsNode().boundsInRoot
            val retry =
                onNode(hasText("Coba lagi") and hasClickAction())
                    .assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp)
            val beforeRetry = retry.fetchSemanticsNode().boundsInRoot
            assertTrue(beforeRetry.top >= 0 && beforeRetry.bottom <= 640)
            assertTrue(beforeRetry.left >= 0 && beforeRetry.right <= 320)
            onNodeWithText("Pembatalan belum terkonfirmasi. Coba lagi untuk memeriksa pesan ini.")
                .performScrollTo()
                .assertIsDisplayed()
            assertEquals(beforeClose, close.fetchSemanticsNode().boundsInRoot)
            assertEquals(beforeRetry, retry.fetchSemanticsNode().boundsInRoot)
            Image.makeFromBitmap(onNode(isDialog()).captureToImage().asSkiaBitmap()).use { image ->
                checkNotNull(image.encodeToData()).use { data ->
                    File("build/reports/screenshots").mkdirs()
                    File("build/reports/screenshots/chat-cancellation-dialog-narrow-large-text.png")
                        .writeBytes(data.bytes)
                }
            }
            retry.performClick()
            close.performClick()
            runOnIdle {
                assertEquals(
                    listOf<ChatThreadEvent>(
                        ChatThreadEvent.CancellationConfirmed,
                        ChatThreadEvent.CancellationDismissed,
                    ),
                    events,
                )
            }
        }
}
