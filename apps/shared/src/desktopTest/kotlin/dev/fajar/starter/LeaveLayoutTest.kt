package dev.fajar.starter

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
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.presentation.overview.*
import dev.fajar.starter.leave.presentation.overview.pages.LeavePage
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class LeaveLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun requestActionStaysVisibleDuringScrollingAndSyncAndIsHiddenAfterDeniedReads() {
        val events = mutableListOf<LeaveEvent>()
        var state by mutableStateOf(LeaveState(content = overview(), loading = false))
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    LeavePage(state, rememberLazyListState(), events::add)
                }
            }
        }
        val action = compose.onNode(hasText("Request leave") and hasClickAction())
        val position = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Cancellation pending"))
        assertEquals(position, action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state = state.copy(refreshing = true) }
        action.assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf<LeaveEvent>(LeaveEvent.CreateRequested), events)
            state =
                state.copy(
                    refreshFailure =
                        Failure(FailureKind.AccessDenied, "Denied", code = "access_denied")
                )
        }
        action.assertDoesNotExist()
    }

    @Test
    fun indonesianLargeTextKeepsBalancesFiltersAndFixedNavigationReadable() {
        val events = mutableListOf<LeaveEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        LeavePage(
                            LeaveState(content = overview(), loading = false),
                            rememberLazyListState(),
                            events::add,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Saldo cuti").assertExists()
        compose.onNode(hasText("Ajukan cuti") and hasClickAction()).assertIsDisplayed()
        compose
            .onNodeWithText("12,5 hari", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        val value =
            compose
                .onNodeWithText("12,5 hari", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(value.left >= frame.left && value.right <= frame.right)
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tahun sebelumnya").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LeaveEvent.YearRequested(2025), events.single()) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Menunggu pembatalan"))
        compose.onNodeWithText("Menunggu pembatalan", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Ajukan cuti") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LeaveEvent.BackRequested, events.last()) }
        val image = Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap())
        val data = checkNotNull(image.encodeToData())
        try {
            File("build/reports/screenshots").mkdirs()
            File("build/reports/screenshots/leave-indonesian-large-text.png").writeBytes(data.bytes)
        } finally {
            data.close()
            image.close()
        }
    }

    private fun overview(): LeaveOverview {
        val now = Instant.parse("2026-10-12T01:00:00Z")
        return LeaveOverview(
            LeaveAccess(
                "session",
                "account",
                "https://hris.example.test/",
                "company",
                setOf("leave.self.manage"),
                now,
                now,
                "expired",
            ),
            "generation",
            LeaveDirectory(
                "account",
                "company",
                LocalDate(2026, 10, 12),
                "Asia/Jakarta",
                listOf(LeaveEmployment("employee", "NUSA-001", LeaveEmploymentStatus.ACTIVE, 0)),
                null,
            ),
            LeaveSelection(employeeId = "employee", year = 2026),
            LeavePage(
                listOf(
                    LeaveBalance(
                        "balance",
                        "type",
                        "ANNUAL",
                        "Cuti tahunan",
                        2026,
                        LeaveDays(25),
                        LeaveDays(4),
                        LeaveDays(3),
                        false,
                        3,
                    )
                )
            ),
            LeavePage(
                listOf(
                    LeaveRequestSummary(
                        "request",
                        "employee",
                        "ANNUAL",
                        "Cuti tahunan",
                        LocalDate(2026, 10, 15),
                        LocalDate(2026, 10, 16),
                        LeaveDays(3),
                        LeaveStatus.CANCELLATION_PENDING,
                        now,
                        2,
                    )
                )
            ),
            now,
            now,
        )
    }
}
