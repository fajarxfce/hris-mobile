package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.fajar.starter.fixtures.*
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workforce.data.overtime.dto.OvertimeDetailsDto
import dev.fajar.starter.workforce.data.overtime.mappers.toDetails
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.presentation.overtime.detail.*
import dev.fajar.starter.workforce.presentation.overtime.detail.pages.OvertimeRequestPage
import dev.fajar.starter.workforce.presentation.overtime.overview.*
import dev.fajar.starter.workforce.presentation.overtime.overview.pages.OvertimePage
import io.github.composefluent.Colors
import io.github.composefluent.FluentTheme
import io.github.composefluent.generateShades
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class OvertimeLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun listRetainsItsTopBarDuringScrollAndRefreshWithoutExposingDeniedRows() {
        val events = mutableListOf<OvertimeEvent>()
        var state by mutableStateOf(overview())
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    OvertimePage(state, rememberLazyListState(), events::add)
                }
            }
        }
        val bar = compose.onNodeWithTag("app-top-bar")
        val position = bar.fetchSemanticsNode().boundsInRoot
        val filter = compose.onNode(hasText("All statuses") and hasClickAction())
        val filterPosition = filter.fetchSemanticsNode().boundsInRoot
        val plan = compose.onNode(hasText("Plan overtime") and hasClickAction())
        val planPosition = plan.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose
            .onNode(hasText("October 2026") and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(OvertimeEvent.MonthFilterRequested, events.single()) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(18)
        assertEquals(position, bar.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.runOnIdle { state = state.copy(refreshing = true) }
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(
            planPosition,
            plan.assertIsDisplayed().assertIsNotEnabled().fetchSemanticsNode().boundsInRoot,
        )
        compose.runOnIdle {
            state =
                state.copy(
                    refreshing = false,
                    refreshFailure = Failure(FailureKind.Network, "Offline"),
                )
        }
        assertEquals(filterPosition, filter.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Unable to connect. Check your connection.").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(OvertimeEvent.RefreshRequested, events.last())
            state =
                state.copy(
                    refreshing = false,
                    cacheFailure =
                        Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
                )
        }
        compose.onNodeWithText("All statuses").assertDoesNotExist()
        plan.assertDoesNotExist()
        compose.onNodeWithText("17:00 – 19:00").assertDoesNotExist()
        compose.onNodeWithText("Verify your account").assertIsDisplayed()
    }

    @Test
    fun detailRefreshFailureIsVisibleAboveTheRetainedScrolledRequest() {
        val snapshot = requestSnapshot()
        val list = LazyListState()
        var state by
            mutableStateOf(
                OvertimeRequestState(snapshot.value.selection, snapshot, loading = false)
            )
        compose.setContent {
            AppTheme { Box(Modifier.size(360.dp, 760.dp)) { OvertimeRequestPage(state, list) {} } }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Approval updated"))
        val bar = compose.onNodeWithTag("app-top-bar").fetchSemanticsNode().boundsInRoot
        val position =
            compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        compose.runOnIdle {
            state = state.copy(refreshFailure = Failure(FailureKind.Network, "Offline"))
        }
        compose.onNodeWithText("Unable to connect. Check your connection.").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(position, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }
        assertEquals(bar, compose.onNodeWithTag("app-top-bar").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed().assertIsEnabled()
        // Feedback reduces the viewport; a row previously at its bottom remains reachable.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Approval updated"))
        compose.onNodeWithText("Approval updated", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun approvedDetailsRemainReadableAtLargeTextInIndonesianDarkTheme() {
        val snapshot = requestSnapshot()
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
                        Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                            OvertimeRequestPage(
                                OvertimeRequestState(
                                    snapshot.value.selection,
                                    snapshot,
                                    loading = false,
                                ),
                                rememberLazyListState(),
                            ) {}
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        compose.onNodeWithText("Durasi disetujui: 1 jam 50 menit").assertExists()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("17:05 – 18:55"))
        val value =
            compose
                .onNodeWithText("17:05 – 18:55", useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(value.left >= frame.left && value.right <= frame.right)
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        capture("overtime-detail-dark-large")
        list.performScrollToNode(hasText("Persetujuan diperbarui"))
        compose.onNodeWithText("Persetujuan diperbarui", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
    }

    @Test
    fun monthSelectionUsesADialogAndCloseStaysAvailableAtLargeText() {
        val events = mutableListOf<OvertimeEvent>()
        val state = overview().copy(dialog = OvertimeDialog.MONTH, pickerYear = 2026)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        OvertimePage(state, rememberLazyListState(), events::add)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed()
        compose.onNode(hasText("Januari") and isSelectable()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf<OvertimeEvent>(OvertimeEvent.PickerMonthSelected(1)), events)
        }
        compose.onNode(hasText("Desember") and isSelectable()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(OvertimeEvent.DialogDismissed, events.last()) }
    }

    @Test
    fun failedHistoryPageCanReturnToLatestWithoutLosingNavigation() {
        val events = mutableListOf<OvertimeRequestEvent>()
        val selection = requestSnapshot().value.selection.copy(historyAfter = 1)
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    OvertimeRequestPage(
                        OvertimeRequestState(
                            selection,
                            loading = false,
                            refreshFailure = Failure(FailureKind.Network, "Offline"),
                        ),
                        rememberLazyListState(),
                        events::add,
                    )
                }
            }
        }
        compose.onNodeWithText("Latest activity").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf<OvertimeRequestEvent>(OvertimeRequestEvent.HistoryPageRequested(null)),
                events,
            )
        }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    private fun overview(): OvertimeState {
        val snapshot = requestSnapshot()
        val selection =
            OvertimeSelection(
                employeeId = NativePeopleUi.EMPLOYEE,
                period = overtimeMonth(LocalDate(2026, 10, 1)),
            )
        val requests =
            (1..20).map { index ->
                snapshot.value.details.request
                    .toSummary()
                    .copy(id = "request-$index", workDate = LocalDate(2026, 10, index))
            }
        val directory =
            WorkforceDirectory(
                NativePeopleUi.ACCOUNT,
                NativeUiServer.COMPANY_ONE,
                LocalDate(2026, 10, 12),
                "Asia/Jakarta",
                listOf(
                    WorkforceEmployment(
                        NativePeopleUi.EMPLOYEE,
                        "NUSA-001",
                        WorkforceEmploymentStatus.ACTIVE,
                        0,
                    )
                ),
                null,
            )
        return OvertimeState(
            selection,
            OvertimeSnapshot(
                snapshot.access,
                "overview",
                OvertimeOverview(
                    directory,
                    selection,
                    dev.fajar.starter.workforce.domain.overtime.entities.OvertimePage(requests),
                ),
                snapshot.fetchedAt,
                snapshot.expiresAt,
            ),
            loading = false,
        )
    }

    private fun requestSnapshot(): OvertimeSnapshot<OvertimeRequestView> {
        val now = Instant.parse("2026-10-12T14:00:00Z")
        val selection =
            OvertimeRequestSelection(NativeOvertimeUi.REQUEST_ID, NativePeopleUi.EMPLOYEE)
        val details =
            Json.decodeFromJsonElement<OvertimeDetailsDto>(
                    nativeOvertimeDetailsFixture(approved = true)
                )
                .toDetails(selection)
        val access =
            WorkforceAccess(
                "session",
                NativePeopleUi.ACCOUNT,
                "https://hris.example.test/",
                NativeUiServer.COMPANY_ONE,
                setOf("overtime.self.manage"),
                now + 1.hours,
                now + 1.hours,
                "expired",
            )
        return OvertimeSnapshot(
            access,
            "generation",
            OvertimeRequestView(selection, details),
            now,
            now + 1.hours,
        )
    }

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
