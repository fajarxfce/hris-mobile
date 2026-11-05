@file:OptIn(
    androidx.compose.ui.test.ExperimentalTestApi::class,
    io.github.composefluent.ExperimentalFluentApi::class,
)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
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
import dev.fajar.starter.collaboration.presentation.thread.pages.ChatForwardPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image

class ChatForwardLayoutTest {
    @Test fun compactPickerKeepsSearchSelectionAndConfirmationReachable() = picker(false, false)

    @Test fun darkLargeTextPickerKeepsItsControlsReachable() = picker(true, false)

    @Test fun landscapePickerCanStillChooseAndForward() = picker(false, true)

    private fun picker(dark: Boolean, landscape: Boolean) =
        runDesktopComposeUiTest(
            width = if (landscape) 760 else 375,
            height = if (landscape) 375 else 780,
        ) {
            val entries =
                listOf("Bima Santoso", "Nadia Pratama", "Raka Wijaya").mapIndexed { index, name ->
                    CollaborationDirectSummary(
                        CollaborationDirectConversation(
                            "chat-$index",
                            "company",
                            "self",
                            "peer-$index",
                            "self",
                            0,
                            0,
                        ),
                        CollaborationParticipant("peer-$index", name),
                    )
                }
            var state by mutableStateOf(ChatForwardState(2, 5, entries, loading = false))
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
                            ChatForwardPage(
                                state,
                                { event ->
                                    events += event
                                    when (event) {
                                        is ChatThreadEvent.ForwardTargetSelected ->
                                            state =
                                                state.copy(
                                                    selectedIds = state.selectedIds + event.id
                                                )
                                        is ChatThreadEvent.ForwardSearchChanged ->
                                            state = state.copy(query = event.query)
                                        else -> Unit
                                    }
                                },
                                Modifier.testTag("forward-frame"),
                            )
                        }
                    }
                }
            }
            onNodeWithText("Teruskan ke").assertIsDisplayed()
            onNodeWithContentDescription("Cari rekan kerja")
                .assertHeightIsAtLeast(48.dp)
                .performTextInput("Nadia")
            onNodeWithTag("chat-forward-target-chat-1")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
                .assertIsOn()
            onNodeWithTag("chat-forward-target-chat-0").assertDoesNotExist()
            onNodeWithTag("chat-forward-confirm")
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHeightIsAtLeast(48.dp)
            Image.makeFromBitmap(onNodeWithTag("forward-frame").captureToImage().asSkiaBitmap())
                .use { image ->
                    checkNotNull(image.encodeToData()).use { data ->
                        File("build/reports/screenshots").mkdirs()
                        File(
                                "build/reports/screenshots/chat-forward-${if (landscape) "landscape" else if (dark) "dark-large-text" else "light"}.png"
                            )
                            .writeBytes(data.bytes)
                    }
                }
            onNodeWithTag("chat-forward-confirm").performClick()
            onNodeWithContentDescription("Kembali")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            runOnIdle {
                assertTrue(ChatThreadEvent.ForwardConfirmed in events)
                assertTrue(ChatThreadEvent.ForwardDismissed in events)
            }
        }
}
