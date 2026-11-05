@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.setup.*
import dev.fajar.starter.collaboration.presentation.setup.pages.ChatSetupPage
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatThreadPage
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image

class ChatReconnectLayoutTest {
    private val rejected =
        Failure(
            FailureKind.Unauthorized,
            "SDK private technical details",
            code = "client.collaboration_sdk_unauthorized",
        )

    @Test
    fun retainedConversationHasOneReconnectActionWithoutRefreshOrGenericRetry() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val events = mutableListOf<ChatThreadEvent>()
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                    AppTheme(reduceMotion = true) {
                        Box(Modifier.fillMaxSize().testTag("reconnect")) {
                            ChatThreadPage(
                                ChatThreadState(
                                    title = "Bima Santoso",
                                    loading = false,
                                    draftReady = true,
                                    connection = ChatThreadConnection.SETUP_REQUIRED,
                                    failure = rejected,
                                    rows =
                                        listOf(
                                            ChatMessageRow(
                                                "one",
                                                "\$one",
                                                null,
                                                1_793_865_600_000,
                                                ChatMessageOwnership.COLLEAGUE,
                                                CollaborationMessageKind.TEXT,
                                                "Please check my transport claim.",
                                                CollaborationMessageDelivery.SENT,
                                            )
                                        ),
                                ),
                                rememberLazyListState(),
                                events::add,
                            )
                        }
                    }
                }
            }
            onNodeWithText("Please check my transport claim.").assertIsDisplayed()
            onAllNodesWithText("Reconnect").assertCountEquals(1)
            onNodeWithText("Retry").assertDoesNotExist()
            onNodeWithText("Refresh").assertDoesNotExist()
            onNodeWithText("SDK private technical details").assertDoesNotExist()
            onNodeWithText("Reconnect to send and receive messages again.").assertDoesNotExist()
            screenshot(onNodeWithTag("reconnect"), "chat-reconnect-local-history")
            onNodeWithText("Reconnect").assertHeightIsAtLeast(48.dp).performClick()
            runOnIdle {
                assertEquals(listOf<ChatThreadEvent>(ChatThreadEvent.SetupRequested), events)
            }
        }

    @Test
    fun indonesianLargeTextReconnectShowsOneExplanationAndKeepsBackReachable() =
        runDesktopComposeUiTest(width = 320, height = 700) {
            val events = mutableListOf<ChatSetupEvent>()
            var state by
                mutableStateOf(
                    ChatSetupState(
                        phase = ChatSetupPhase.RECONNECT,
                        loading = false,
                        failure = rejected,
                    )
                )
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2f),
                    LocalAppLanguage provides AppLanguage.Indonesian,
                ) {
                    AppTheme(reduceMotion = true) {
                        Box(Modifier.fillMaxSize().testTag("reconnect")) {
                            ChatSetupPage(
                                state,
                                { event ->
                                    events += event
                                    if (event == ChatSetupEvent.ContinueRequested)
                                        state = state.copy(working = true)
                                },
                            )
                        }
                    }
                }
            }
            val back = onNodeWithContentDescription("Kembali")
            back.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            onAllNodesWithText("Sambungkan kembali untuk mengirim dan menerima pesan.")
                .assertCountEquals(1)
            onNodeWithText("Muat ulang").assertDoesNotExist()
            onNodeWithText("Coba lagi").assertDoesNotExist()
            onNodeWithText("Sambungkan kembali")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
            screenshot(onNodeWithTag("reconnect"), "chat-reconnect-indonesian-large")
            onNodeWithText("Sambungkan kembali").performClick().assertIsNotEnabled()
            back.performClick()
            runOnIdle {
                assertEquals(
                    listOf<ChatSetupEvent>(
                        ChatSetupEvent.ContinueRequested,
                        ChatSetupEvent.BackRequested,
                    ),
                    events,
                )
            }
        }

    private fun screenshot(node: SemanticsNodeInteraction, name: String) {
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/$name.png").writeBytes(data.bytes)
            }
        }
    }
}
