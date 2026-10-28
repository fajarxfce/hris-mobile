package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.presentation.nativepush.*
import dev.fajar.starter.notifications.presentation.nativepush.widgets.NativePushSettingsCard
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.presentation.preferences.SettingsState
import dev.fajar.starter.settings.presentation.preferences.pages.SettingsPage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class NativePushSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pendingIndonesianSettingsKeepRetryReachableAndNavigationFixedAtLargeText() {
        val events = mutableListOf<NativePushEvent>()
        var state by
            mutableStateOf(
                NativePushState(
                    "local",
                    status(enabled = true, pending = true),
                    busy = true,
                    failure = Failure(FailureKind.Network, "Provider diagnostics must stay hidden"),
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalAppLanguage provides AppLanguage.Indonesian,
                LocalDensity provides Density(density.density, 2f),
            ) {
                AppTheme(reduceMotion = true) {
                    FluentTheme(
                        colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = true)
                    ) {
                        Box(Modifier.size(360.dp, 760.dp)) {
                            SettingsPage(SettingsState(), {}) {
                                NativePushSettingsCard(state, events::add)
                            }
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali")
        val top = back.fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Coba lagi").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Provider diagnostics must stay hidden").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(busy = false) }
        compose.onNodeWithText("Coba lagi").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf<NativePushEvent>(NativePushEvent.RetryRequested), events)
        }
        back.assertIsDisplayed()
        assertEquals(top, back.fetchSemanticsNode().boundsInRoot.top)
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                val file = File("build/reports/screenshots/native-push-indonesian-large-text.png")
                file.parentFile.mkdirs()
                file.writeBytes(data.bytes)
            }
        }
    }

    @Test
    fun theSwitchForwardsExplicitActionsAndUnsupportedHostsCannotEnablePush() {
        val events = mutableListOf<NativePushEvent>()
        var state by mutableStateOf(NativePushState("local", status(enabled = false)))
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme(reduceMotion = true) {
                    Box(Modifier.width(360.dp)) { NativePushSettingsCard(state, events::add) }
                }
            }
        }
        val toggle = compose.onNodeWithText("Inbox notifications")
        toggle.assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(listOf<NativePushEvent>(NativePushEvent.EnableRequested), events)
            state = state.copy(status = status(enabled = true), busy = true)
        }
        toggle.assertIsOn().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false) }
        toggle.performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(NativePushEvent.EnableRequested, NativePushEvent.DisableRequested),
                events,
            )
            state = state.copy(status = status(enabled = false).copy(supported = false))
        }
        toggle.assertIsNotEnabled().assertIsOff()
        compose
            .onNodeWithText("Push notifications are available in the Android and iOS apps.")
            .assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    private fun status(enabled: Boolean, pending: Boolean = false) =
        NativePushStatus(true, enabled, enabled, pending, NotificationAccess.Granted, null)
}
