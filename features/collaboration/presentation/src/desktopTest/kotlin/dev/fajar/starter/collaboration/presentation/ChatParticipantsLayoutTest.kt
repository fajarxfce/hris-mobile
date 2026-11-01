package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.CollaborationPage
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxFixture
import dev.fajar.starter.collaboration.presentation.participants.*
import dev.fajar.starter.collaboration.presentation.participants.pages.ChatParticipantsPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ChatParticipantsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeIndonesianSearchKeepsBackAndInputVisibleAndSelectionReleasesFocus() {
        val f = ChatInboxFixture()
        val people = (1..40).map { f.item(it).peer.copy(displayName = "Rekan Kerja Contoh $it") }
        val events = mutableListOf<ChatParticipantsEvent>()
        var state by
            mutableStateOf(
                ChatParticipantsState(page = CollaborationPage(people, null), loading = false)
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatParticipantsPage(
                            state,
                            rememberLazyListState(),
                            onEvent = { event ->
                                events += event
                                if (event is ChatParticipantsEvent.QueryChanged)
                                    state = state.copy(query = event.value)
                                if (event is ChatParticipantsEvent.ParticipantSelected)
                                    state =
                                        state.copy(
                                            selected =
                                                people.single { it.accountId == event.accountId },
                                            opening = true,
                                        )
                            },
                        )
                    }
                }
            }
        }
        val search = compose.onNodeWithContentDescription("Cari rekan kerja")
        val back = compose.onNodeWithContentDescription("Kembali")
        search.assertIsFocused().assertHeightIsAtLeast(48.dp).performTextInput("Rekan")
        val inputBounds = search.fetchSemanticsNode().boundsInRoot
        val backBounds =
            back
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(backBounds.right <= inputBounds.left && inputBounds.right <= frame.right)
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Rekan Kerja Contoh 40"))
        assertEquals(inputBounds, search.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        back.assertIsDisplayed()
        screenshot("chat-participants-indonesian-large-text")
        compose.onNodeWithText("Rekan Kerja Contoh 40").performClick()
        search.assertIsNotEnabled()
        assertFalse(
            search.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
        )
        compose.onNodeWithText("Membuka percakapan…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(
                ChatParticipantsEvent.ParticipantSelected(people.last().accountId),
                events.last(),
            )
        }
    }

    @Test
    fun failedOpenRetainsChosenPersonAndRetryCannotBeSubmittedTwice() {
        val f = ChatInboxFixture()
        val person = f.item(1).peer.copy(displayName = "Nadia Pratama")
        val events = mutableListOf<ChatParticipantsEvent>()
        var state by
            mutableStateOf(
                ChatParticipantsState(
                    query = "Nadia",
                    page = CollaborationPage(listOf(person), null),
                    loading = false,
                    selected = person,
                    openFailure = f.network,
                )
            )
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    ChatParticipantsPage(
                        state,
                        rememberLazyListState(),
                        onEvent = { event ->
                            events += event
                            if (event == ChatParticipantsEvent.RetryOpenRequested)
                                state = state.copy(opening = true, openFailure = null)
                            if (event == ChatParticipantsEvent.CancelSelectionRequested)
                                state =
                                    state.copy(selected = null, opening = false, openFailure = null)
                        },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Find a colleague").assertIsNotEnabled()
        compose.onNodeWithText("Nadia Pratama").assertIsDisplayed()
        val retry =
            compose.onNode(hasText("Retry") and hasClickAction()).assertHeightIsAtLeast(48.dp)
        val cancel =
            compose.onNode(hasText("Cancel") and hasClickAction()).assertHeightIsAtLeast(48.dp)
        screenshot("chat-participants-retry")
        retry.performClick()
        retry.assertDoesNotExist()
        compose.onNodeWithText("Opening conversation…").assertIsDisplayed()
        cancel.performClick()
        compose
            .onNodeWithContentDescription("Find a colleague")
            .assertIsEnabled()
            .assertIsFocused()
            .assertTextContains("Nadia")
        compose.runOnIdle {
            assertEquals(
                listOf<ChatParticipantsEvent>(
                    ChatParticipantsEvent.RetryOpenRequested,
                    ChatParticipantsEvent.CancelSelectionRequested,
                ),
                events,
            )
        }
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
