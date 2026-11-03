package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalList
import dev.fajar.starter.collaboration.presentation.inbox.*
import dev.fajar.starter.collaboration.presentation.inbox.pages.ChatInboxPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ChatInboxLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowIndonesianLargeTextKeepsNavigationAndRowsAccessible() {
        val fixture = ChatInboxFixture()
        val rows =
            (1..30).map {
                fixture.item(it).let { row ->
                    row.copy(peer = row.peer.copy(displayName = "Rekan Kerja Contoh $it"))
                }
            }
        val events = mutableListOf<ChatInboxEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatInboxPage(
                            ChatInboxState(
                                page = CollaborationLocalList(null, rows, "next"),
                                loading = false,
                            ),
                            rememberLazyListState(),
                            events::add,
                        )
                    }
                }
            }
        }
        val back =
            compose
                .onNodeWithContentDescription("Kembali")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        val newChat =
            compose
                .onNodeWithContentDescription("Chat baru")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Chat").performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(titleLayouts)
        }
        assertEquals(1, titleLayouts.single().lineCount)
        assertFalse(titleLayouts.single().hasVisualOverflow)
        val bounds = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        val actionBounds = newChat.fetchSemanticsNode().boundsInRoot
        assertTrue(actionBounds.right <= bounds.right && actionBounds.left >= bounds.left)
        compose
            .onNodeWithText("Rekan Kerja Contoh 1")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Rekan Kerja Contoh 30"))
        compose.onNodeWithText("Rekan Kerja Contoh 30").assertIsDisplayed()
        back.assertIsDisplayed()
        newChat.performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    ChatInboxEvent.ConversationSelected(rows.first().conversation.id),
                    ChatInboxEvent.NewChatRequested,
                ),
                events,
            )
        }
        screenshot("chat-inbox-indonesian-large-text")
    }

    @Test
    fun failedRefreshRetainsClickableSavedRowsAndSingleRetry() {
        val fixture = ChatInboxFixture()
        val events = mutableListOf<ChatInboxEvent>()
        var state by
            mutableStateOf(
                ChatInboxState(
                    page = CollaborationLocalList(null, listOf(fixture.item(1)), null),
                    loading = false,
                    refreshFailure = fixture.network,
                )
            )
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    ChatInboxPage(state, rememberLazyListState(), events::add)
                }
            }
        }
        compose.onNodeWithText("Showing conversations saved on this device.").assertIsDisplayed()
        compose.onNodeWithText("Colleague 1").performClick()
        val retry = compose.onNode(hasText("Retry") and hasClickAction())
        retry.performClick()
        compose.runOnIdle { state = state.copy(refreshing = true) }
        retry.assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    ChatInboxEvent.ConversationSelected(fixture.item(1).conversation.id),
                    ChatInboxEvent.RefreshRequested,
                ),
                events,
            )
        }
        compose.onNodeWithText("Colleague 1").assertIsDisplayed()
    }

    @Test
    fun initialRefreshShowsProgressWithoutPrematureEmptyState() {
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    ChatInboxPage(
                        ChatInboxState(
                            page = CollaborationLocalList(null, emptyList(), null),
                            loading = false,
                            refreshing = true,
                        ),
                        rememberLazyListState(),
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("No conversations yet").assertDoesNotExist()
        compose.onNodeWithText("Loading").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh").assertIsNotEnabled()
    }

    @Test
    fun aSavedEmptyInboxRemainsReadableDuringBackgroundRefresh() {
        compose.setContent {
            AppTheme {
                ChatInboxPage(
                    ChatInboxState(
                        page =
                            CollaborationLocalList(
                                dev.fajar.starter.collaboration.domain.entities
                                    .CollaborationListCheckpoint(
                                        "generation",
                                        1,
                                        0,
                                        kotlin.time.Instant.parse("2026-11-03T00:00:00Z"),
                                    ),
                                emptyList(),
                                null,
                            ),
                        loading = false,
                        refreshing = true,
                    ),
                    rememberLazyListState(),
                    {},
                )
            }
        }
        compose.onNodeWithText("Loading").assertDoesNotExist()
        compose.onNodeWithText("No conversations yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("New chat").assertIsEnabled()
    }

    @Test
    fun ordinaryListUsesCompactRowsAndDecorativeInitialsStaySilent() {
        val fixture = ChatInboxFixture()
        val names =
            listOf(
                "Nadia Pratama",
                "Rizky Saputra",
                "Dewi Lestari",
                "Bima Wijaya",
                "Sari Handayani",
                "Agus Setiawan",
                "Maya Anggraini",
                "Fajar Nugroho",
            )
        val rows =
            names.mapIndexed { index, name ->
                fixture.item(index + 1).let { it.copy(peer = it.peer.copy(displayName = name)) }
            }
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    ChatInboxPage(
                        ChatInboxState(
                            page = CollaborationLocalList(null, rows, null),
                            loading = false,
                        ),
                        rememberLazyListState(),
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText(names.first()).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("NP").assertDoesNotExist()
        compose.onNodeWithText(names.last()).assertIsDisplayed()
        screenshot("chat-inbox-english")
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
