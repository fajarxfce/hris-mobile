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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
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
class ChatEditorLayoutTest {
    @Test fun compactEditorKeepsTheOriginalAndDraftReachable() = editor(false, false)

    @Test fun largeTextEditorKeepsActionsAndFailureReachable() = editor(true, false)

    @Test fun landscapeEditorPreservesRoomAndComposerSpace() = editor(false, true)

    private fun editor(dark: Boolean, landscape: Boolean) =
        runDesktopComposeUiTest(
            width = if (landscape) 760 else 375,
            height = if (landscape) 375 else 780,
        ) {
            val original = original()
            var state by
                mutableStateOf(
                    ChatThreadState(
                        title = "Nadia Pratama",
                        loading = false,
                        draftReady = true,
                        draft = "Draft berikutnya tetap tersimpan.",
                        rows = listOf(original),
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
                            ChatThreadPage(
                                state,
                                rememberLazyListState(),
                                { event ->
                                    events += event
                                    state = reduce(state, event, original)
                                },
                                Modifier.testTag("editor-frame"),
                                today = LocalDate(2026, 11, 5),
                            )
                        }
                    }
                }
            }
            onNodeWithText("Edit", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("Tindakan lainnya").assertDoesNotExist()
            onNodeWithTag("chat-message-original").performTouchInput { longClick() }
            onNodeWithContentDescription("Tindakan lainnya").performClick()
            onNodeWithText("Edit").assertIsDisplayed().performClick()
            onNodeWithContentDescription("Edit pesan")
                .assertIsEnabled()
                .performClick()
                .assertIsFocused()
                .performTextReplacement("Rapat dipindah ke pukul sepuluh.")
            onNodeWithContentDescription("Simpan perubahan")
                .assertIsEnabled()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            onNodeWithContentDescription("Batalkan edit")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            onNode(
                    hasText(original.text!!) and
                        hasAnyAncestor(hasTestTag("chat-message-original")),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assertCountEquals(0)
            screenshot(
                onNodeWithTag("editor-frame"),
                if (landscape) "landscape" else if (dark) "dark-large-text" else "light",
            )
            runOnIdle { state = state.copy(editing = state.editing!!.copy(locked = true)) }
            onNodeWithContentDescription("Edit pesan").performTextInputSelection(TextRange(0, 5))
            onNodeWithContentDescription("Edit pesan")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.TextSelectionRange,
                        TextRange(0, 5),
                    )
                )
            onNodeWithContentDescription("Batalkan edit").performClick()
            onNodeWithContentDescription("Pesan")
                .assertTextContains("Draft berikutnya tetap tersimpan.")
            onNodeWithTag("chat-message-original").performTouchInput { longClick() }
            onNodeWithContentDescription("Tindakan lainnya").performClick()
            onNodeWithText("Edit").performClick()
            onNodeWithContentDescription("Edit pesan")
                .performTextReplacement("Rapat pukul sepuluh.")
            onNodeWithContentDescription("Simpan perubahan").performClick()
            onNodeWithContentDescription("Edit pesan").assertDoesNotExist()
            onNodeWithContentDescription("Pesan")
                .assertTextContains("Draft berikutnya tetap tersimpan.")
            onNode(
                    hasText("Rapat pukul sepuluh.") and
                        hasAnyAncestor(hasTestTag("chat-message-original")),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            onNodeWithContentDescription("Perubahan menunggu dikirim", useUnmergedTree = true)
                .assertIsDisplayed()
            runOnIdle {
                state =
                    state.copy(
                        rows = listOf(original.copy(failedEditIds = listOf("reviewed-edit")))
                    )
            }
            onNodeWithText("Perubahan tidak diterapkan.", useUnmergedTree = true)
                .assertIsDisplayed()
            onNodeWithTag("chat-message-original").performTouchInput { longClick() }
            onNodeWithContentDescription("Tindakan lainnya").performClick()
            onNodeWithText("Buang perubahan").assertIsEnabled().performClick()
            runOnIdle {
                assertTrue(
                    events.containsAll(
                        listOf(
                            ChatThreadEvent.EditCancelled,
                            ChatThreadEvent.EditSaveRequested,
                            ChatThreadEvent.FailedEditsDismissRequested,
                        )
                    )
                )
                assertFalse(events.contains(ChatThreadEvent.SendRequested))
                assertEquals(original.timestamp, state.rows.single().timestamp)
                assertEquals(original.reference, state.rows.single().reference)
            }
        }

    private fun reduce(
        state: ChatThreadState,
        event: ChatThreadEvent,
        original: ChatMessageRow,
    ): ChatThreadState =
        when (event) {
            is ChatThreadEvent.MessageSelected ->
                state.copy(
                    selecting = true,
                    selectedMessages = listOf(state.rows.single()),
                    selectionTime = original.timestamp + 1,
                )
            ChatThreadEvent.SelectionMenuOpened -> state.copy(selectionMenuOpen = true)
            ChatThreadEvent.SelectionMenuDismissed -> state.copy(selectionMenuOpen = false)
            ChatThreadEvent.EditSelected ->
                state.copy(
                    selecting = false,
                    selectedMessages = emptyList(),
                    editing = ChatEditState(candidate(), original.text!!),
                )
            is ChatThreadEvent.EditChanged ->
                state.copy(editing = state.editing!!.copy(text = event.text))
            ChatThreadEvent.EditCancelled -> state.copy(editing = null)
            ChatThreadEvent.EditSaveRequested ->
                state.copy(
                    editing = null,
                    rows =
                        listOf(
                            original.copy(
                                text = state.editing!!.text,
                                editDelivery = CollaborationMessageDelivery.PENDING,
                            )
                        ),
                )
            else -> state
        }

    private fun original() =
        ChatMessageRow(
            "original",
            "\$original",
            null,
            1_793_865_600_000,
            ChatMessageOwnership.SELF,
            CollaborationMessageKind.TEXT,
            "Rapat dimulai pukul sembilan.",
            CollaborationMessageDelivery.SENT,
            reference = CollaborationMessageReference("@self:example.test", "\$original"),
            visibilityKnown = true,
            editEligibleUntil = 1_793_866_500_000,
        )

    private fun candidate() =
        CollaborationEditCandidate(
            CollaborationEditTarget(
                "!room:example.test",
                "\$original",
                null,
                "@self:example.test",
                "a".repeat(64),
                original().timestamp,
                original().timestamp,
            ),
            original().text!!,
        )

    private fun screenshot(node: SemanticsNodeInteraction, name: String) {
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/chat-editor-$name.png").writeBytes(data.bytes)
            }
        }
    }
}
