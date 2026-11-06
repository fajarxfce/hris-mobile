@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import kotlin.test.Test

class ChatSendFeedbackTest {
    @Test
    fun normalSubmissionDoesNotClaimThatSavingWasInterrupted() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            var state by
                mutableStateOf(
                    ChatThreadState(
                        title = "Colleague",
                        draftReady = true,
                        loading = false,
                        draft = "AAA",
                        saving = true,
                    )
                )
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        ChatThreadPage(state, rememberLazyListState(), {})
                    }
                }
            }
            onNodeWithText("Saving was interrupted. Retry to confirm this message.")
                .assertDoesNotExist()
            runOnIdle { state = state.copy(submissionPending = true) }
            onNodeWithText("AAA").assertIsDisplayed()
            onNodeWithText("Saving was interrupted. Retry to confirm this message.")
                .assertDoesNotExist()
            runOnIdle { state = state.copy(saving = false, submissionPending = false, draft = "") }
            onNodeWithText("Saving was interrupted. Retry to confirm this message.")
                .assertDoesNotExist()
        }

    @Test
    fun uncertainSubmissionKeepsRecoveryFeedbackUntilAnAttemptStarts() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            var state by
                mutableStateOf(
                    ChatThreadState(
                        title = "Colleague",
                        draftReady = true,
                        loading = false,
                        draft = "Preserved message",
                        submissionPending = true,
                    )
                )
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        ChatThreadPage(state, rememberLazyListState(), {})
                    }
                }
            }
            onNodeWithText("Saving was interrupted. Retry to confirm this message.")
                .assertIsDisplayed()
            onNodeWithText("Preserved message").assertIsDisplayed()
            runOnIdle { state = state.copy(saving = true) }
            onNodeWithText("Saving was interrupted. Retry to confirm this message.")
                .assertDoesNotExist()
            runOnIdle { state = state.copy(saving = false) }
            onNodeWithText("Saving was interrupted. Retry to confirm this message.")
                .assertIsDisplayed()
        }
}
