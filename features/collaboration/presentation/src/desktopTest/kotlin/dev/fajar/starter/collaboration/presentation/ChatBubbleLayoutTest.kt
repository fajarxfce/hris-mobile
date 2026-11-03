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
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalFluentApi::class)
class ChatBubbleLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactBubblesGroupDatesAndKeepQueueStatusAccessible() = render(false, false, false)

    @Test fun darkLargeTextKeepsTheComposerAccessible() = render(true, true, false)

    @Test fun landscapeKeepsInputAndBackAlongsideHistory() = render(false, false, true)

    private fun render(dark: Boolean, large: Boolean, landscape: Boolean) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (large) 2f else 1f),
                LocalAppLanguage provides AppLanguage.English,
            ) {
                AppTheme(reduceMotion = true) {
                    FluentTheme(
                        colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = dark)
                    ) {
                        Box(
                            Modifier.size(
                                    if (landscape) 760.dp else 375.dp,
                                    if (landscape) 375.dp else 780.dp,
                                )
                                .testTag("chat-frame")
                        ) {
                            ChatThreadPage(
                                ChatThreadState(
                                    title = "Nadia Pratama",
                                    loading = false,
                                    draftReady = true,
                                    draft = "Thanks, I'll check it.",
                                    rows = rows(),
                                ),
                                rememberLazyListState(),
                                {},
                                today = LocalDate(2026, 11, 3),
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Message").assertIsDisplayed().assertIsEnabled()
        compose
            .onNodeWithContentDescription("Send message")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Waiting to send").assertIsDisplayed()
        if (!large && !landscape) {
            compose.onNodeWithText("Today").assertIsDisplayed()
            compose.onNodeWithText("Yesterday").assertIsDisplayed()
            compose.onNodeWithContentDescription("Sent").assertIsDisplayed()
            compose.onNodeWithText("03/11/2026").assertDoesNotExist()
        }
        val name = if (landscape) "landscape" else if (dark) "dark-large-text" else "light"
        Image.makeFromBitmap(compose.onNodeWithTag("chat-frame").captureToImage().asSkiaBitmap())
            .use { image ->
                checkNotNull(image.encodeToData()).use { data ->
                    File("build/reports/screenshots").mkdirs()
                    File("build/reports/screenshots/chat-bubbles-$name.png").writeBytes(data.bytes)
                }
            }
    }

    private fun rows() =
        listOf(
            row(
                "queued",
                "I'll send the receipt when I'm back online.",
                "2026-11-03T08:15:00Z",
                true,
                CollaborationMessageDelivery.PENDING,
            ),
            row("peer", "Could you check my reimbursement?", "2026-11-03T08:14:00Z", false),
            row("sent", "Hi!", "2026-11-02T08:10:00Z", true),
        )

    private fun row(
        id: String,
        text: String,
        at: String,
        own: Boolean,
        delivery: CollaborationMessageDelivery = CollaborationMessageDelivery.SENT,
    ) =
        ChatMessageRow(
            id,
            if (delivery == CollaborationMessageDelivery.PENDING) null else id,
            null,
            Instant.parse(at).toEpochMilliseconds(),
            if (own) ChatMessageOwnership.SELF else ChatMessageOwnership.COLLEAGUE,
            CollaborationMessageKind.TEXT,
            text,
            delivery,
        )
}
