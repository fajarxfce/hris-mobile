package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.home.presentation.datasync.*
import dev.fajar.starter.home.presentation.datasync.pages.DataSyncPage
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import io.github.composefluent.Colors
import io.github.composefluent.FluentTheme
import io.github.composefluent.generateShades
import java.io.File
import kotlin.test.*
import kotlin.time.Instant
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class DataSyncLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowLargeTextLayoutKeepsNavigationAndActionsReachableWhileScrolling() {
        val events = mutableListOf<DataSyncEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme(reduceMotion = true) {
                    Box(Modifier.size(360.dp, 560.dp).testTag("frame")) {
                        DataSyncPage(state(), events::add)
                    }
                }
            }
        }
        val bar = compose.onNodeWithTag("app-top-bar")
        val initial = bar.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Sync all data").assertIsDisplayed()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("data-sync-Payroll"))
        val action = compose.onNodeWithContentDescription("Sync Payslips")
        val bounds = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= frame.left && bounds.right <= frame.right)
        assertTrue(bounds.width >= 48 && bounds.height >= 48)
        action.performClick()
        assertEquals(initial, bar.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf<DataSyncEvent>(
                    DataSyncEvent.DatasetRefreshRequested(CompanyDataSet.Payroll),
                    DataSyncEvent.BackRequested,
                ),
                events,
            )
        }
        capture("data-sync-large")
    }

    @Test
    fun refreshKeepsItsActionBoundsAndOffersStopWithoutHidingSavedStatus() {
        val events = mutableListOf<DataSyncEvent>()
        var current by mutableStateOf(state())
        compose.setContent {
            AppTheme(reduceMotion = true) {
                Box(Modifier.size(360.dp, 760.dp)) { DataSyncPage(current, events::add) }
            }
        }
        val action = compose.onNodeWithContentDescription("Sync My profile")
        val initial = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        action.performClick()
        compose.runOnIdle {
            current =
                current.copy(
                    rows =
                        current.rows.map {
                            if (it.dataSet == CompanyDataSet.Profile)
                                it.copy(progress = DataSyncProgress.Running)
                            else it
                        }
                )
        }
        assertEquals(
            initial,
            action.assertIsDisplayed().assertIsNotEnabled().fetchSemanticsNode().boundsInRoot,
        )
        compose.onNodeWithContentDescription("Sync all data").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop syncing").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(DataSyncEvent.CancelRequested, events.last())
            current =
                current.copy(
                    rows =
                        current.rows.map {
                            if (it.dataSet == CompanyDataSet.Profile)
                                it.copy(
                                    progress = DataSyncProgress.Idle,
                                    syncFailure =
                                        Failure(
                                            FailureKind.Network,
                                            "Internal transport diagnostic",
                                        ),
                                )
                            else it
                        }
                )
        }
        compose.onNodeWithText("Unable to connect. Check your connection.").assertIsDisplayed()
        compose.onNodeWithText("Internal transport diagnostic").assertDoesNotExist()
        action.assertIsEnabled()
        compose.onNodeWithContentDescription("Sync all data").assertIsDisplayed()
        capture("data-sync-offline")
    }

    @Test
    fun confirmedDenialHasOneVerificationActionInIndonesianDarkTheme() {
        val events = mutableListOf<DataSyncEvent>()
        val denial =
            Failure(FailureKind.AccessDenied, "Internal access diagnostic", code = "mfa_required")
        val current =
            state().let { initial ->
                initial.copy(
                    rows = initial.rows.map { it.copy(status = null, readFailure = denial) }
                )
            }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme(reduceMotion = true) {
                    FluentTheme(
                        colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = true)
                    ) {
                        Box(Modifier.size(360.dp, 760.dp)) { DataSyncPage(current, events::add) }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        compose.onNodeWithText("Data dan sinkronisasi").assertIsDisplayed()
        compose.onAllNodesWithText("Verifikasi akun").assertCountEquals(1)
        compose.onNodeWithText("Verifikasi akun").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf<DataSyncEvent>(DataSyncEvent.VerificationRequested), events)
        }
        compose.onNodeWithText("Internal access diagnostic").assertDoesNotExist()
        capture("data-sync-dark-large")
    }

    private fun state() =
        DataSyncState(
            "PT Nusa Digital",
            CompanyDataSet.entries.map {
                DataSyncRowState(
                    it,
                    CompanyDataStatus(Instant.parse("2026-10-17T00:00:00Z")),
                    loading = false,
                )
            },
        )

    private fun capture(name: String) {
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use { image ->
            requireNotNull(image.encodeToData()).use { data ->
                val file = File("build/reports/screenshots/$name.png")
                file.parentFile.mkdirs()
                file.writeBytes(data.bytes)
            }
        }
    }
}
