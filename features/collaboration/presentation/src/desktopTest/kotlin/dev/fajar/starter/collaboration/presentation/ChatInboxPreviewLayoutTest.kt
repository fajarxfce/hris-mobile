package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.*
import dev.fajar.starter.collaboration.presentation.inbox.pages.ChatInboxPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalFluentApi::class)
class ChatInboxPreviewLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedTextAndDraftUseCompactDates() = render(false, false)

    @Test fun darkLargeTextKeepsDraftAndConversationAccessible() = render(true, false)

    @Test fun landscapeKeepsListRowsAndActionsReachable() = render(false, true)

    private fun render(large: Boolean, landscape: Boolean) {
        val f = ChatInboxFixture()
        val names =
            listOf(
                "Rani Kusuma (Demo)",
                "Bima Santoso (Demo)",
                "Nadia Pratama (Demo)",
                "Dimas Saputra (Demo)",
            )
        val rows =
            names.mapIndexed { index, name ->
                f.item(index + 1).let { it.copy(peer = it.peer.copy(displayName = name)) }
            }
        val dates =
            listOf(
                "2026-11-03T10:15:00Z",
                "2026-11-02T08:10:00Z",
                "2026-10-31T08:10:00Z",
                "2026-10-11T08:10:00Z",
            )
        val messages =
            listOf(
                "Boleh bantu cek klaim reimbursement ini?",
                "Dokumennya sudah aku terima.",
                "Terima kasih, sampai ketemu Senin.",
                "Pesan lama untuk pemeriksaan tata letak.",
            )
        val previews =
            rows
                .mapIndexed { i, row ->
                    val message =
                        CollaborationHistoryMessage(
                            "@peer:example.test",
                            null,
                            "\$event-$i",
                            Instant.parse(dates[i]).toEpochMilliseconds(),
                            "a".repeat(64),
                            if (i == 1 || i == 2) "Older stored preview" else messages[i],
                            listOf("\$event-$i"),
                            false,
                        )
                    row.conversation.id to
                        CollaborationInboxPreview(
                            message,
                            if (i == 0)
                                CollaborationDraft(
                                    row.conversation.id,
                                    chatId(9000),
                                    "Aku lengkapi kuitansinya sebentar lagi.",
                                    Instant.parse(dates[0]),
                                )
                            else null,
                            outgoing =
                                if (i == 1 || i == 2)
                                    CollaborationMessageRecord(
                                        CollaborationMessageIntent(
                                            chatId(9100 + i),
                                            CollaborationMessageOwner(
                                                f.server.audience,
                                                f.initial.user.id,
                                                row.conversation.companyId,
                                            ),
                                            row.conversation.id,
                                            Instant.parse(dates[i]),
                                            messages[i],
                                        ),
                                        0,
                                        if (i == 1) CollaborationMessageState.Queued
                                        else
                                            CollaborationMessageState.Sent(
                                                CollaborationMessageDevice(
                                                    "native",
                                                    "device",
                                                    "bootstrap",
                                                    "!room:example.test",
                                                ),
                                                "txn",
                                                "\$event-$i",
                                            ),
                                    )
                                else null,
                        )
                }
                .toMap()
        val events = mutableListOf<ChatInboxEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (large) 2f else 1f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme(reduceMotion = true) {
                    FluentTheme(
                        colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = large)
                    ) {
                        Box(
                            Modifier.size(
                                    if (landscape) 760.dp else 375.dp,
                                    if (landscape) 375.dp else 780.dp,
                                )
                                .testTag("inbox-frame")
                        ) {
                            ChatInboxPage(
                                ChatInboxState(
                                    page = CollaborationLocalList(null, rows, null),
                                    loading = false,
                                    previews = previews,
                                ),
                                rememberLazyListState(),
                                events::add,
                                onBack = null,
                                zone = TimeZone.UTC,
                                today = LocalDate(2026, 11, 3),
                            )
                        }
                    }
                }
            }
        }
        compose
            .onNodeWithContentDescription("Chat baru")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Rani Kusuma (Demo)", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithText("Draf: Aku lengkapi kuitansinya sebentar lagi.", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("10:15", useUnmergedTree = true).assertIsDisplayed()
        if (!large && !landscape) {
            compose
                .onNodeWithContentDescription("Menunggu dikirim", useUnmergedTree = true)
                .assertIsDisplayed()
            compose.onNodeWithText(messages[1], useUnmergedTree = true).assertIsDisplayed()
            compose
                .onNodeWithText("Older stored preview", useUnmergedTree = true)
                .assertDoesNotExist()
        }
        if (!large && !landscape) {
            compose.onNodeWithText("Kemarin", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Sabtu", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("11/10/2026", useUnmergedTree = true).assertIsDisplayed()
        }
        val name = if (landscape) "landscape" else if (large) "dark-large-text" else "light"
        Image.makeFromBitmap(compose.onNodeWithTag("inbox-frame").captureToImage().asSkiaBitmap())
            .use { image ->
                checkNotNull(image.encodeToData()).use { data ->
                    File("build/reports/screenshots").mkdirs()
                    File("build/reports/screenshots/chat-inbox-previews-$name.png")
                        .writeBytes(data.bytes)
                }
            }
        if (large) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText("Dimas Saputra (Demo)"))
            compose
                .onNodeWithText("Dimas Saputra (Demo)", useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().hasVisualOverflow)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Rani Kusuma (Demo)"))
        compose.onNode(hasText("Rani Kusuma (Demo)") and hasClickAction()).performClick()
        assertEquals(
            ChatInboxEvent.ConversationSelected(rows.first().conversation.id),
            events.single(),
        )
    }
}
