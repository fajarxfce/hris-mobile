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
import dev.fajar.starter.collaboration.presentation.replies.*
import dev.fajar.starter.collaboration.presentation.replies.pages.SavedRepliesPage
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class SavedRepliesLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactListShowsRecipientAndSavedTextWithOneNavigationAction() {
        val row = savedReply()
        val events = mutableListOf<SavedRepliesEvent>()
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    SavedRepliesPage(
                        SavedRepliesState(snapshot = snapshot(row), loading = false),
                        rememberLazyListState(),
                        events::add,
                    )
                }
            }
        }
        compose.onNodeWithText("Nadia Pratama").assertIsDisplayed()
        screenshot("chat-saved-replies")
        compose.onNodeWithText("Nadia Pratama").performClick()
        compose
            .onNodeWithContentDescription("Back")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.runOnIdle {
            assertEquals(
                listOf<SavedRepliesEvent>(SavedRepliesEvent.ReplySelected(row.input.id)),
                events,
            )
        }
    }

    @Test
    fun heldDetailLabelsItsSavedTextAndOffersExplicitSend() {
        val row = savedReply()
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    SavedRepliesPage(
                        SavedRepliesState(row.input.id, snapshot(row), loading = false),
                        rememberLazyListState(),
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("Saved reply").assertIsDisplayed()
        compose.onNodeWithText("Your reply").assertIsDisplayed()
        compose.onNodeWithText(row.input.candidate.text).assertIsDisplayed()
        compose.onNodeWithText("Open chat").assertIsDisplayed()
        compose.onNodeWithText("Discard saved reply").assertIsDisplayed()
        compose.onNodeWithText("Send reply").assertIsDisplayed()
        screenshot("chat-saved-reply")
    }

    @Test
    fun narrowLargeTextDetailKeepsReplyAndActionsReachable() {
        val row = savedReply()
        val events = mutableListOf<SavedRepliesEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        SavedRepliesPage(
                            SavedRepliesState(row.input.id, snapshot(row), loading = false),
                            rememberLazyListState(),
                            events::add,
                        )
                    }
                }
            }
        }
        compose
            .onNodeWithContentDescription("Kembali")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Balasan Anda").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(row.input.candidate.text).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Buang balasan tersimpan")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf<SavedRepliesEvent>(SavedRepliesEvent.DiscardRequested), events)
        }
        compose.onNodeWithTag("frame").performTouchInput { swipeUp() }
        compose.onNodeWithText("Buang balasan tersimpan").assertIsDisplayed()
        screenshot("chat-saved-reply-indonesian-large-text")
    }

    @Test
    fun deniedStateHidesPrivateContentAndConfirmedOutcomeDoesNotClaimDelivery() {
        val row = savedReply()
        var state by mutableStateOf(SavedRepliesState(row.input.id, snapshot(row), loading = false))
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    SavedRepliesPage(state, rememberLazyListState(), {})
                }
            }
        }
        compose.onNodeWithText(row.input.candidate.text).assertIsDisplayed()
        compose.runOnIdle {
            state =
                SavedRepliesState(
                    row.input.id,
                    loading = false,
                    failure = Failure(FailureKind.AccessDenied, "Unavailable"),
                )
        }
        compose.onNodeWithText(row.input.candidate.text).assertDoesNotExist()
        compose.onNodeWithText("Nadia Pratama").assertDoesNotExist()
        compose.runOnIdle {
            state =
                SavedRepliesState(
                    row.input.id,
                    snapshot(emptyList()),
                    loading = false,
                    outcome = CollaborationReplyDiscard.Discarded,
                )
        }
        compose.onNodeWithText("Saved reply discarded.").assertIsDisplayed()
        compose.onNodeWithText("Sent").assertDoesNotExist()
    }

    @Test
    fun editorForwardsKeyboardChangesAndSendAsSeparateActions() {
        val row = savedReply()
        val events = mutableListOf<SavedRepliesEvent>()
        var state by mutableStateOf(SavedRepliesState(row.input.id, snapshot(row), loading = false))
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    SavedRepliesPage(
                        state,
                        rememberLazyListState(),
                        { event ->
                            events += event
                            if (event is SavedRepliesEvent.DraftChanged)
                                state = state.copy(draft = event.text, savingDraft = true)
                        },
                    )
                }
            }
        }
        compose
            .onNode(hasSetTextAction())
            .performClick()
            .performTextReplacement("Updated fictional reply\nPlease check the attached claim.")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("Saving draft…").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.all { it is SavedRepliesEvent.DraftChanged }) }
        screenshot("chat-reviewed-reply-editor")
        compose.onNodeWithText("Send reply").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SavedRepliesEvent.SendRequested, events.last()) }
    }

    @Test
    fun confirmedReplyKeepsItsBodyReadOnlyAndRetryExplicit() {
        val row =
            savedReply().let {
                it.copy(
                    input =
                        it.input.copy(
                            review = null,
                            resolution =
                                CollaborationNotificationReplyResolution(
                                    it.input.candidate.parent,
                                    id(99),
                                    kotlin.time.Instant.fromEpochMilliseconds(1_793_491_200_000),
                                    "Confirmed fictional reply",
                                ),
                        )
                )
            }
        val events = mutableListOf<SavedRepliesEvent>()
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    SavedRepliesPage(
                        SavedRepliesState(row.input.id, snapshot(row), loading = false),
                        rememberLazyListState(),
                        events::add,
                    )
                }
            }
        }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Confirmed fictional reply").assertIsDisplayed()
        compose.onNodeWithText("Continue sending").performClick()
        compose.runOnIdle {
            assertEquals(listOf<SavedRepliesEvent>(SavedRepliesEvent.SendRequested), events)
        }
        screenshot("chat-reviewed-reply-confirmed")
    }

    @Test
    fun startedOriginalDisablesSendingAndOffersDiscardOfOnlyTheSavedEdit() {
        val row =
            savedReply().let {
                it.copy(
                    input =
                        it.input.copy(
                            review = CollaborationNotificationReplyReview.OriginalProcessing,
                            resolution =
                                CollaborationNotificationReplyResolution(
                                    it.input.candidate.parent,
                                    id(99),
                                    kotlin.time.Instant.fromEpochMilliseconds(1_793_491_200_000),
                                    "Saved fictional edit",
                                ),
                        )
                )
            }
        val events = mutableListOf<SavedRepliesEvent>()
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    SavedRepliesPage(
                        SavedRepliesState(row.input.id, snapshot(row), loading = false),
                        rememberLazyListState(),
                        events::add,
                    )
                }
            }
        }
        compose.onNodeWithText("Continue sending").assertIsNotEnabled()
        compose.onNodeWithText("Discard saved reply").assertDoesNotExist()
        compose.onNodeWithText("Discard saved edit").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf<SavedRepliesEvent>(SavedRepliesEvent.DiscardEditRequested), events)
        }
        screenshot("chat-reviewed-reply-original-processing")
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

    private fun snapshot(row: CollaborationSavedReply) = snapshot(listOf(row))

    private fun snapshot(rows: List<CollaborationSavedReply>) =
        CollaborationSavedReplies(
            CollaborationLocalAccess("local", "https://hris.example.test/", id(1), id(2)),
            rows,
            CollaborationNativeParent("local", "https://hris.example.test/", id(1), id(3)),
        )

    private fun savedReply() =
        CollaborationSavedReply(
            CollaborationNotificationReplyInput(
                "n-" + "a".repeat(64),
                CollaborationNotificationReplyRevision(id(6), 0),
                0,
                CollaborationNotificationReplyCandidate(
                    CollaborationNativeParent("local", "https://hris.example.test/", id(1), id(3)),
                    CollaborationNotificationAction.Reply(
                        id(4),
                        CollaborationNotificationActionTarget(
                            id(2),
                            id(5),
                            "!room:example.test",
                            "\$event",
                            id(7),
                            id(8),
                        ),
                    ),
                    CollaborationNotificationActionToken("v1.fixture"),
                    "Terima kasih, dokumennya sudah saya periksa.",
                ),
                CollaborationNotificationReplyReview.SessionChanged,
            ),
            "Nadia Pratama",
        )

    private fun id(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
}
