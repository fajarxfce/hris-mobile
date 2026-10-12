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
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.fixtures.nativeLeaveRequestFixture
import dev.fajar.starter.leave.data.dto.LeaveRequestDto
import dev.fajar.starter.leave.data.mappers.toDetails
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.presentation.detail.*
import dev.fajar.starter.leave.presentation.detail.pages.LeaveRequestPage
import dev.fajar.starter.leave.presentation.detail.widgets.LeaveActionDialog
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class LeaveRequestLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun requestActionWaitsForRecoveryAndKeepsItsPositionWhileHistoryRefreshes() {
        val events = mutableListOf<LeaveRequestEvent>()
        var state by mutableStateOf(LeaveRequestState(content = snapshot(), loading = false))
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    LeaveRequestPage(state, rememberLazyListState(), events::add)
                }
            }
        }
        val action = compose.onNode(hasText("Withdraw cancellation") and hasClickAction())
        val position =
            action.assertIsDisplayed().assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = state.copy(action = state.action.copy(ready = true)) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Earlier updates"))
        action.assertIsDisplayed().assertIsEnabled()
        assertEquals(position, action.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state = state.copy(refreshing = true) }
        action.assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            state = state.copy(refreshing = false)
        }
        action.performClick()
        compose.runOnIdle {
            assertEquals(
                listOf<LeaveRequestEvent>(
                    LeaveRequestEvent.ActionRequested(LeaveRequestAction.WITHDRAW_CANCELLATION)
                ),
                events,
            )
        }
    }

    @Test
    fun cancellationDialogKeepsMultilineReasonConfirmationAndCloseReachableAtLargeText() {
        val events = mutableListOf<LeaveRequestEvent>()
        var form by
            mutableStateOf(
                LeaveActionForm(
                    LeaveRequestAction.REQUEST_CANCELLATION,
                    12,
                    "Jadwal berubah.\nMohon ditinjau kembali.",
                )
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    LeaveActionDialog(form, false) { event ->
                        events += event
                        if (event is LeaveRequestEvent.ReasonChanged)
                            form = form.copy(reason = event.value)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Alasan")
            .performScrollTo()
            .performTextReplacement("Rencana berubah\nTinjau kembali")
        val confirmation = compose.onNode(hasText("Ajukan pembatalan") and hasClickAction())
        confirmation.performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Rencana berubah\nTinjau kembali", form.reason)
            assertEquals(LeaveRequestEvent.ActionConfirmed, events.last())
        }
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LeaveRequestEvent.ActionDismissed, events.last()) }
        val image = Image.makeFromBitmap(compose.onNode(isDialog()).captureToImage().asSkiaBitmap())
        image.use { captured ->
            checkNotNull(captured.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/leave-cancellation-dialog-large-text.png")
                    .writeBytes(data.bytes)
            }
        }
    }

    @Test
    fun largeIndonesianTextKeepsStatusDatesHistoryAndFixedBackActionReadable() {
        val events = mutableListOf<LeaveRequestEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        LeaveRequestPage(
                            LeaveRequestState(content = snapshot(), loading = false),
                            rememberLazyListState(),
                            events::add,
                        )
                    }
                }
            }
        }
        compose
            .onNodeWithText("1,5 hari", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onNodeWithText("Menunggu pembatalan", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        val status =
            compose
                .onNodeWithText("Menunggu pembatalan", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(status.left >= frame.left && status.right <= frame.right)
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Setengah hari pertama"))
        compose.onNodeWithText("Setengah hari pertama", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Perubahan sebelumnya"))
        compose.onNodeWithText("Perubahan sebelumnya").performClick()
        compose.runOnIdle {
            assertEquals(LeaveRequestEvent.HistoryPageRequested("3"), events.single())
        }
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LeaveRequestEvent.BackRequested, events.last()) }
        val image = Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap())
        val data = checkNotNull(image.encodeToData())
        try {
            File("build/reports/screenshots").mkdirs()
            File("build/reports/screenshots/leave-request-indonesian-large-text.png")
                .writeBytes(data.bytes)
        } finally {
            data.close()
            image.close()
        }
    }

    private fun snapshot(): LeaveRequestSnapshot {
        val now = Instant.parse("2026-10-12T01:00:00Z")
        val selection = LeaveRequestSelection("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        val details =
            Json.decodeFromJsonElement<LeaveRequestDto>(nativeLeaveRequestFixture(null))
                .toDetails(selection)
        return LeaveRequestSnapshot(
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
            selection,
            details.copy(
                status = LeaveStatus.CANCELLATION_PENDING,
                availableActions = setOf(LeaveAction.WITHDRAW),
                cancellation =
                    LeaveWorkflow(
                        "ffffffff-ffff-4fff-8fff-ffffffffffff",
                        LeaveWorkflowStatus.PENDING,
                        0,
                        0,
                    ),
            ),
            now,
            now,
        )
    }
}
