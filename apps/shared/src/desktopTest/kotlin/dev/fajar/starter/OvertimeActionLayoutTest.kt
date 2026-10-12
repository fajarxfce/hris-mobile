package dev.fajar.starter

import androidx.compose.foundation.layout.*
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
import dev.fajar.starter.localization.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workforce.data.overtime.dto.OvertimeDetailsDto
import dev.fajar.starter.workforce.data.overtime.mappers.toDetails
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import dev.fajar.starter.workforce.presentation.overtime.action.pages.OvertimeActionPage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class OvertimeActionLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeActualInputKeepsBackAndConfirmationAboveTheScrollingContent() {
        var state by mutableStateOf(initial(OvertimeActionKind.ACTUAL))
        val events = mutableListOf<OvertimeActionEvent>()
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
                            OvertimeActionPage(state, rememberLazyListState()) { event ->
                                events += event
                                if (event is OvertimeActionEvent.ReasonChanged)
                                    state =
                                        state.copy(form = state.form!!.copy(reason = event.value))
                            }
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot
        val submit = compose.onNode(hasText("Kirim realisasi") and hasClickAction())
        val position = submit.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription("Alasan"))
        compose.onNodeWithContentDescription("Alasan").performTextReplacement("Inventaris selesai")
        assertEquals(
            back,
            compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(position, submit.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        submit.performClick()
        compose.runOnIdle {
            assertEquals("Inventaris selesai", state.form?.reason)
            assertEquals(OvertimeActionEvent.SubmitRequested, events.last())
            state = state.copy(work = OvertimeActionWork.SENDING)
        }
        submit.assertIsNotEnabled()
        capture("overtime-actual-large-text")
    }

    @Test
    fun withdrawalUsesAnExplicitReasonWithoutTimeControlsAndDenialHidesItsContents() {
        var state by mutableStateOf(initial(OvertimeActionKind.WITHDRAW))
        val events = mutableListOf<OvertimeActionEvent>()
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                    OvertimeActionPage(state, rememberLazyListState(), events::add)
                }
            }
        }
        compose.onNodeWithText("This will withdraw the overtime request.").assertExists()
        compose.onNode(hasText("Start time") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Reason").assertExists()
        compose
            .onNode(hasText("Withdraw request") and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf<OvertimeActionEvent>(OvertimeActionEvent.SubmitRequested), events)
        }
        capture("overtime-withdrawal")
        compose.runOnIdle {
            state =
                state.copy(
                    restriction =
                        Failure(
                            FailureKind.AccessDenied,
                            "Private diagnostic",
                            code = "mfa_required",
                        )
                )
        }
        compose.onNodeWithContentDescription("Reason").assertDoesNotExist()
        compose.onNode(hasText("Withdraw request") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Private diagnostic").assertDoesNotExist()
        compose.onNodeWithText("Verify your account").assertIsDisplayed()
    }

    @Test
    fun actualDatesAreLimitedToThePlannedIntervalAndChangedRequestsRequireReview() {
        var state by
            mutableStateOf(
                initial(OvertimeActionKind.ACTUAL)
                    .copy(
                        dialog =
                            OvertimeActionDialog.Date(
                                OvertimeActualField.END,
                                LocalDate(2026, 10, 1),
                            )
                    )
            )
        val events = mutableListOf<OvertimeActionEvent>()
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    OvertimeActionPage(state, rememberLazyListState(), events::add)
                }
            }
        }
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-11", "en"))
            .assertIsNotEnabled()
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-12", "en"))
            .assertIsEnabled()
            .performClick()
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-13", "en"))
            .assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf<OvertimeActionEvent>(OvertimeActionEvent.DateSelected(12)), events)
            val content = state.content!!
            val selection = content.value.selection
            val changed =
                Json.decodeFromJsonElement<OvertimeDetailsDto>(
                        nativeOvertimeDetailsFixture(approved = true)
                    )
                    .toDetails(selection)
            state =
                state.copy(
                    dialog = null,
                    content = content.copy(value = content.value.copy(details = changed)),
                )
        }
        compose.onNode(hasText("Submit actual time") and hasClickAction()).assertDoesNotExist()
        compose
            .onNode(hasText("View request") and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(OvertimeActionEvent.OpenRequestRequested, events.last()) }
    }

    private fun initial(kind: OvertimeActionKind): OvertimeActionState {
        val now = Instant.parse("2026-10-12T13:00:00Z")
        val selection =
            OvertimeRequestSelection(NativeOvertimeUi.REQUEST_ID, NativePeopleUi.EMPLOYEE)
        val details =
            Json.decodeFromJsonElement<OvertimeDetailsDto>(nativeOvertimeDetailsFixture())
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
        val snapshot =
            OvertimeSnapshot(
                access,
                "generation",
                OvertimeRequestView(selection, details),
                now,
                now + 1.hours,
            )
        return OvertimeActionState(
            OvertimeActionTarget("workspace", selection, kind),
            snapshot,
            OvertimeActionForm(
                0,
                if (kind == OvertimeActionKind.ACTUAL)
                    OvertimeActualInput(
                        LocalDateTime(2026, 10, 12, 17, 0),
                        LocalDateTime(2026, 10, 12, 19, 0),
                        "0",
                    )
                else null,
            ),
            loading = false,
            commandReady = true,
        )
    }

    private fun capture(name: String) {
        Image.makeFromBitmap(compose.onNodeWithTag("frame").captureToImage().asSkiaBitmap()).use {
            image ->
            requireNotNull(image.encodeToData()).use { data ->
                val file = File("build/reports/screenshots/$name.png")
                file.parentFile.mkdirs()
                file.writeBytes(data.bytes)
            }
        }
    }
}
