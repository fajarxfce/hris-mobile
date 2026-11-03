package dev.fajar.starter

import androidx.compose.foundation.background
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
import dev.fajar.starter.app.navigation.NativeLinkStatus
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class NativeLinkStatusLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun failureOffersRetryAndDismissWithoutTakingOverThePage() {
        val actions = mutableListOf<String>()
        compose.setContent {
            AppTheme {
                Column(
                    Modifier.size(360.dp, 480.dp)
                        .testTag("frame")
                        .background(AppColors.canvas)
                        .padding(16.dp)
                ) {
                    NativeLinkStatus(
                        false,
                        Failure(FailureKind.Unavailable, "Private diagnostic"),
                        true,
                        { actions += "retry" },
                        { actions += "dismiss" },
                        { actions += "company" },
                    )
                    AppText("Page content remains available")
                }
            }
        }
        compose.onNodeWithText("Could not open this conversation. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Private diagnostic").assertDoesNotExist()
        compose.onNodeWithText("Choose a company").assertDoesNotExist()
        compose.onNodeWithText("Page content remains available").assertIsDisplayed()
        screenshot("chat-native-link-error")
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(listOf("retry", "dismiss"), actions) }
    }

    @Test
    fun narrowLargeTextVerificationKeepsCompanyRetryAndDismissReachable() {
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Column(
                        Modifier.size(320.dp, 640.dp)
                            .testTag("frame")
                            .background(AppColors.canvas)
                            .padding(16.dp)
                    ) {
                        NativeLinkStatus(
                            false,
                            Failure(FailureKind.AccessDenied, "Private", code = "mfa_required"),
                            true,
                            { actions += "retry" },
                            { actions += "dismiss" },
                            { actions += "company" },
                        )
                        AppText("Konten halaman")
                    }
                }
            }
        }
        compose
            .onNodeWithText("Verifikasi akun dan pilih perusahaan, lalu coba lagi.")
            .assertIsDisplayed()
        compose.onNodeWithText("Pilih perusahaan").assertIsDisplayed().performClick()
        compose.onNodeWithText("Coba lagi").assertIsDisplayed().performClick()
        compose.onNodeWithText("Tutup").assertIsDisplayed().performClick()
        compose.onNodeWithText("Konten halaman").assertIsDisplayed()
        screenshot("chat-native-link-verification-indonesian-large-text")
        compose.runOnIdle { assertEquals(listOf("company", "retry", "dismiss"), actions) }
    }

    @Test
    fun openingCanBeCancelledAndIdleStatusDoesNotConsumePageSpace() {
        var opening by mutableStateOf(true)
        var cancelled = false
        compose.setContent {
            AppTheme {
                Column(
                    Modifier.size(360.dp, 480.dp)
                        .testTag("frame")
                        .background(AppColors.canvas)
                        .padding(16.dp)
                ) {
                    NativeLinkStatus(
                        opening,
                        null,
                        false,
                        {},
                        {
                            cancelled = true
                            opening = false
                        },
                        {},
                    )
                    AppText("Page content")
                }
            }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Page content").assertIsDisplayed()
        compose.runOnIdle { assertTrue(cancelled) }
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
