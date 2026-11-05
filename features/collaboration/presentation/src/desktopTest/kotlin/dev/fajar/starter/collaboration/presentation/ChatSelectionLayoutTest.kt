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
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatLocalDeletionDialog
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
class ChatSelectionLayoutTest {
    @Test fun longPressThenTapSelectsMultipleMessagesInCompactLayout() = selection(false, false)

    @Test fun darkLargeTextKeepsSelectionActionsReachable() = selection(true, false)

    @Test fun landscapeKeepsSelectionAndMessageActionsVisible() = selection(false, true)

    private fun selection(dark: Boolean, landscape: Boolean) =
        runDesktopComposeUiTest(
            width = if (landscape) 760 else 375,
            height = if (landscape) 375 else 780,
        ) {
            val messages =
                listOf(
                    row("one", "Bisa cek klaim transport saya?", false),
                    row("two", "Bisa, saya cek setelah rapat.", true),
                )
            var state by
                mutableStateOf(
                    ChatThreadState(
                        title = "Nadia Pratama",
                        loading = false,
                        draftReady = true,
                        rows = messages,
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
                            Box(Modifier.fillMaxSize().testTag("selection-frame")) {
                                ChatThreadPage(
                                    state,
                                    rememberLazyListState(),
                                    { event ->
                                        events += event
                                        when (event) {
                                            is ChatThreadEvent.MessageSelected ->
                                                state =
                                                    state.copy(
                                                        selecting = true,
                                                        selectedMessages =
                                                            state.selectedMessages +
                                                                messages.single {
                                                                    it.key == event.key
                                                                },
                                                    )
                                            ChatThreadEvent.SelectionCleared ->
                                                state =
                                                    state.copy(
                                                        selecting = false,
                                                        selectedMessages = emptyList(),
                                                    )
                                            else -> Unit
                                        }
                                    },
                                    today = LocalDate(2026, 11, 5),
                                )
                            }
                        }
                    }
                }
            }
            onNodeWithTag("chat-message-one").performTouchInput { longClick() }
            onNodeWithTag("chat-message-one").assertIsSelected()
            onNodeWithTag("chat-message-two").performClick().assertIsSelected()
            onNodeWithText("2 dipilih").assertIsDisplayed()
            onNodeWithContentDescription("Salin")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            onNodeWithContentDescription("Hapus pesan")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            onNodeWithContentDescription("Pesan").assertIsNotEnabled()
            screenshot(
                onNodeWithTag("selection-frame"),
                if (landscape) "landscape" else if (dark) "dark-large-text" else "light",
            )
            onNodeWithContentDescription("Batal pilih").performClick()
            onNodeWithContentDescription("Kembali").assertIsDisplayed()
            onNodeWithContentDescription("Pesan").assertIsEnabled()
            runOnIdle {
                assertTrue(
                    events.containsAll(
                        listOf(
                            ChatThreadEvent.CopySelected,
                            ChatThreadEvent.DeleteSelected,
                            ChatThreadEvent.SelectionCleared,
                        )
                    )
                )
            }
        }

    @Test
    fun localDeleteDialogKeepsConfirmationAndEscapeReachableWithLargeText() =
        runDesktopComposeUiTest(width = 320, height = 640) {
            val events = mutableListOf<ChatThreadEvent>()
            var state by mutableStateOf(ChatDeletionDialog(50))
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2f),
                    LocalAppLanguage provides AppLanguage.Indonesian,
                ) {
                    AppTheme(reduceMotion = true) {
                        ChatLocalDeletionDialog(state) { event ->
                            events += event
                            if (event == ChatThreadEvent.DeletionConfirmed)
                                state = state.copy(busy = true)
                        }
                    }
                }
            }
            onNodeWithText("Hapus untuk saya").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            screenshot(onNode(isDialog()), "delete-large-text")
            onNodeWithText("Hapus untuk saya").performClick()
            onNode(hasClickAction() and hasText("Hapus untuk saya")).assertIsNotEnabled()
            onNodeWithText("Batal").assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(
                    listOf(ChatThreadEvent.DeletionConfirmed, ChatThreadEvent.DeletionDismissed),
                    events,
                )
            }
        }

    private fun row(id: String, text: String, own: Boolean) =
        ChatMessageRow(
            id,
            "\$$id",
            null,
            1_793_865_600_000,
            if (own) ChatMessageOwnership.SELF else ChatMessageOwnership.COLLEAGUE,
            CollaborationMessageKind.TEXT,
            text,
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
                File("build/reports/screenshots/chat-selection-$name.png").writeBytes(data.bytes)
            }
        }
    }
}
