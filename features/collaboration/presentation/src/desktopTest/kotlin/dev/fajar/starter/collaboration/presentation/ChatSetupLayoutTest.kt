package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.setup.*
import dev.fajar.starter.collaboration.presentation.setup.pages.ChatSetupPage
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ChatSetupLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeIndonesianSetupKeepsNavigationSeparateAndActionReachable() {
        val events = mutableListOf<ChatSetupEvent>()
        var state by
            mutableStateOf(ChatSetupState(phase = ChatSetupPhase.REGISTER, loading = false))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(320.dp, 640.dp).testTag("frame")) {
                        ChatSetupPage(
                            state,
                            onEvent = { event ->
                                events += event
                                if (event == ChatSetupEvent.ContinueRequested)
                                    state = state.copy(working = true)
                            },
                        )
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali")
        val bounds =
            back
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode()
                .boundsInRoot
        compose
            .onNodeWithText("Aktifkan chat")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertEquals(bounds, back.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        screenshot("chat-setup-indonesian-large-text")
        compose.onNodeWithText("Aktifkan chat").performClick().assertIsNotEnabled()
        back.performClick()
        compose.runOnIdle {
            assertEquals(
                listOf<ChatSetupEvent>(
                    ChatSetupEvent.ContinueRequested,
                    ChatSetupEvent.BackRequested,
                ),
                events,
            )
        }
    }

    @Test
    fun readyRequiresAnOwnedDeviceBeforeOpeningChat() {
        val events = mutableListOf<ChatSetupEvent>()
        var state by mutableStateOf(ChatSetupState(phase = ChatSetupPhase.READY, loading = false))
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        ChatSetupPage(state, onEvent = { events += it })
                    }
                }
            }
        }
        compose.onNodeWithText("Open chat").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(readyDeviceId = "fixture-device") }
        screenshot("chat-setup-ready")
        compose.onNodeWithText("Open chat").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf<ChatSetupEvent>(ChatSetupEvent.OpenChatRequested), events)
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
