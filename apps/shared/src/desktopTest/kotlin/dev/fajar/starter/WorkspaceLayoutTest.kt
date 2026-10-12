package dev.fajar.starter

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
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workspace.presentation.selection.*
import dev.fajar.starter.workspace.presentation.selection.pages.WorkspacePage
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class WorkspaceLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun companyRowsExposeOneActionAndKeepPendingFeedbackAndSignOutVisibleAtLargeText() {
        val events = mutableListOf<WorkspaceEvent>()
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        WorkspacePage(
                            WorkspaceState(
                                companies =
                                    listOf(
                                        CompanyMembership(
                                            "first",
                                            "PT Nusa Digital",
                                            "NUSA",
                                            "Asia/Jakarta",
                                        ),
                                        CompanyMembership(
                                            "second",
                                            "PT Lintas Karya",
                                            "LINTAS",
                                            "Asia/Jakarta",
                                        ),
                                    ),
                                selectingId = selected,
                            )
                        ) { event ->
                            events += event
                            if (event is WorkspaceEvent.CompanySelected) selected = event.companyId
                        }
                    }
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).performClick()
        compose.onNode(hasText("PT Nusa Digital") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Membuka perusahaan…", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNode(hasText("Keluar") and hasClickAction())
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose.onNodeWithTag("app-top-bar").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf<WorkspaceEvent>(WorkspaceEvent.CompanySelected("first")), events)
        }
        val image = Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap())
        image.use { captured ->
            checkNotNull(captured.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/company-picker-large-text.png")
                    .writeBytes(data.bytes)
            }
        }
    }
}
