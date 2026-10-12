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
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import dev.fajar.starter.workforce.presentation.overtime.plan.pages.OvertimePlanPage
import dev.fajar.starter.workforce.presentation.overtime.plan.widgets.OvertimePlanPicker
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class OvertimePlanLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeIndonesianFormKeepsInputBackAndThePrimaryActionReachable() {
        var state by mutableStateOf(preparedState())
        val events = mutableListOf<OvertimePlanEvent>()
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
                            OvertimePlanPage(state, rememberLazyListState()) { event ->
                                events += event
                                if (event is OvertimePlanEvent.ReasonChanged)
                                    state =
                                        state.copy(form = state.form!!.copy(reason = event.value))
                            }
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot
        val submit = compose.onNode(hasText("Kirim rencana") and hasClickAction())
        val actionPosition = submit.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription("Alasan"))
        compose
            .onNodeWithContentDescription("Alasan")
            .performTextReplacement("Inventaris akhir bulan")
        compose.runOnIdle { assertEquals("Inventaris akhir bulan", state.form?.reason) }
        assertEquals(
            back,
            compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(actionPosition, submit.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        submit.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(OvertimePlanEvent.SubmitRequested, events.last())
            state = state.copy(work = OvertimePlanWork.SENDING)
        }
        submit.assertIsNotEnabled()
        capture("overtime-plan-large-text", compose.onNodeWithTag("frame"))
    }

    @Test
    fun aLargeTimeDialogUsesControlledNumericFieldsAndRetainsItsCloseTarget() {
        var state by
            mutableStateOf(
                preparedState()
                    .copy(dialog = OvertimePlanDialog.Time(OvertimeTimeField.START, "18", "30"))
            )
        val events = mutableListOf<OvertimePlanEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    OvertimePlanPicker(state) { event ->
                        events += event
                        if (event is OvertimePlanEvent.HoursChanged)
                            state =
                                state.copy(
                                    dialog =
                                        (state.dialog as OvertimePlanDialog.Time).copy(
                                            hours = event.value
                                        )
                                )
                    }
                }
            }
        }
        val close =
            compose
                .onNodeWithContentDescription("Tutup")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        compose
            .onNodeWithContentDescription("Jam (0–23)")
            .performScrollTo()
            .performTextReplacement("99")
        compose
            .onNode(hasText("Gunakan jam") and hasClickAction())
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription("Jam (0–23)").performTextReplacement("21")
        compose.onNode(hasText("Gunakan jam") and hasClickAction()).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(OvertimePlanEvent.TimeConfirmed, events.last()) }
        assertEquals(
            close,
            compose
                .onNodeWithContentDescription("Tutup")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot,
        )
        capture("overtime-plan-time-large-text", compose.onNode(isDialog()))
    }

    @Test
    fun theDatePickerRestrictsExecutionDatesAndForwardsOneSelection() {
        val events = mutableListOf<OvertimePlanEvent>()
        val state =
            preparedState()
                .copy(
                    dialog =
                        OvertimePlanDialog.Date(OvertimePlanDateField.END, LocalDate(2026, 10, 1))
                )
        compose.setContent { AppTheme { OvertimePlanPicker(state, events::add) } }
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-11", "en"))
            .assertIsNotEnabled()
            .performClick()
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-13", "en"))
            .assertIsEnabled()
            .performClick()
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-15", "en"))
            .assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf<OvertimePlanEvent>(OvertimePlanEvent.DateSelected(13)), events)
        }
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun uncertainDeliveryShowsOneRecoveryPathAndDenialHidesRetainedContent() {
        val initial = preparedState()
        val prepared = initial.prepared!!
        val command =
            OvertimeCommand(
                "33333333-3333-4333-8333-333333333333",
                prepared.access.accountId,
                prepared.access.audience,
                prepared.access.companyId,
                NativeOvertimeUi.REQUEST_ID,
                NativePeopleUi.EMPLOYEE,
                "NUSA-001",
                null,
                prepared.selection.workDate,
                "Asia/Jakarta",
                OvertimeCommandPayload.Plan(
                    0,
                    OvertimeInterval(
                        Instant.parse("2026-10-12T11:30:00Z"),
                        Instant.parse("2026-10-12T13:30:00Z"),
                        15,
                        105,
                    ),
                    "a".repeat(64),
                ),
                "Inventory count",
                prepared.fetchedAt,
            )
        var state by
            mutableStateOf(
                initial.copy(
                    prepared = null,
                    command =
                        OvertimeCommandRecord(command, 1, 1, OvertimeCommandOutcome.Unconfirmed()),
                    work = OvertimePlanWork.SENDING,
                )
            )
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    OvertimePlanPage(state, rememberLazyListState()) {}
                }
            }
        }
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        compose.onNodeWithText("Retry delivery").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(work = null) }
        compose.onNodeWithText("Retry delivery").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Discard saved input").assertDoesNotExist()
        compose.onNodeWithText("Submit plan").assertDoesNotExist()
        compose.runOnIdle {
            state =
                state.copy(
                    restriction = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
                )
        }
        compose.onNodeWithText("Inventory count").assertDoesNotExist()
        compose.onNodeWithText("Retry delivery").assertDoesNotExist()
        compose.onNodeWithText("Verify your account").assertIsDisplayed()
    }

    private fun preparedState(): OvertimePlanState {
        val date = LocalDate(2026, 10, 12)
        val now = Instant.parse("2026-10-12T01:00:00Z")
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
        val review =
            OvertimePlanningReview(
                WorkforceEmployment(
                    NativePeopleUi.EMPLOYEE,
                    "NUSA-001",
                    WorkforceEmploymentStatus.ACTIVE,
                    0,
                ),
                date,
                date,
                "Asia/Jakarta",
                WorkDay.Off(date, CalendarOrigin.ROSTER, 0, null),
                "a".repeat(64),
            )
        return OvertimePlanState(
            form = OvertimePlanForm(date, startsAt = LocalTime(18, 30), endsAt = LocalTime(20, 30)),
            prepared =
                OvertimePreparation(
                    access,
                    OvertimePlanningSelection(NativePeopleUi.EMPLOYEE, date),
                    review,
                    now,
                    now + 60.seconds,
                ),
            commandReady = true,
        )
    }

    private fun capture(name: String, node: SemanticsNodeInteraction) {
        val directory = File("build/ui-previews").apply { mkdirs() }
        val bitmap = node.captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            image.encodeToData()?.use { File(directory, "$name.png").writeBytes(it.bytes) }
        }
    }
}
