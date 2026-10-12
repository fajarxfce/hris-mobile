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
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.leave.presentation.create.pages.LeaveCreatePage
import dev.fajar.starter.leave.presentation.create.widgets.LeaveCreatePicker
import dev.fajar.starter.localization.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class LeaveCreateLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun retainedAttemptDoesNotOfferRetryUntilTheCurrentDeliveryFinishes() {
        val base = previewState()
        val preparation = base.preview!!
        val command =
            LeaveSubmissionCommand(
                "44444444-4444-4444-8444-444444444444",
                preparation.access.accountId,
                preparation.access.audience,
                preparation.access.companyId,
                "55555555-5555-4555-8555-555555555555",
                preparation.selection,
                preparation.preview.revision,
                preparation.preview.policy.name,
                preparation.preview.charged,
                "Family appointment",
                emptyList(),
                preparation.fetchedAt,
            )
        var state by
            mutableStateOf(
                base.copy(
                    command =
                        LeaveSubmissionRecord(command, 1, 1, LeaveCommandOutcome.Unconfirmed()),
                    preview = null,
                    work = LeaveCreateWork.SENDING,
                )
            )
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    LeaveCreatePage(state, rememberLazyListState()) {}
                }
            }
        }
        compose.onNodeWithText("Sending request…").assertExists()
        compose.onNodeWithText("The delivery result is not yet known.").assertDoesNotExist()
        compose.onNodeWithText("Retry delivery").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(work = null) }
        compose.onNodeWithText("Sending request…").assertDoesNotExist()
        compose.onNodeWithText("The delivery result is not yet known.").assertExists()
        compose.onNode(hasText("Retry delivery") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun largeIndonesianFormKeepsReasonCalculationAndFixedBackActionReachable() {
        val events = mutableListOf<LeaveCreateEvent>()
        var state by mutableStateOf(previewState())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        LeaveCreatePage(state, rememberLazyListState()) { event ->
                            events += event
                            if (event is LeaveCreateEvent.ReasonChanged)
                                state = state.copy(form = state.form!!.copy(reason = event.value))
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot
        compose
            .onNodeWithContentDescription("Alasan")
            .performScrollTo()
            .performTextReplacement("Keperluan keluarga\nMohon ditinjau")
        compose.runOnIdle { assertEquals("Keperluan keluarga\nMohon ditinjau", state.form?.reason) }
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Tanggal tidak dihitung"))
        compose
            .onNode(hasText(formatLocalDate("2026-10-17", "id")) and hasClickAction().not())
            .assertExists()
        compose
            .onNode(hasText("Kirim pengajuan") and hasClickAction())
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(LeaveCreateEvent.SubmitRequested, events.last())
            state = state.copy(previewExpired = true)
        }
        compose
            .onNode(hasText("Kirim pengajuan") and hasClickAction())
            .performScrollTo()
            .assertIsNotEnabled()
        assertEquals(
            back,
            compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot,
        )
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        capture("leave-create-large-text", compose.onRoot())
    }

    @Test
    fun endDatePickerDisablesEarlierDatesAndKeepsItsCloseActionVisible() {
        val state =
            previewState()
                .copy(dialog = LeaveCreateDialog.Date(LeaveDateField.UNTIL, LocalDate(2026, 10, 1)))
        val events = mutableListOf<LeaveCreateEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme { LeaveCreatePicker(state, events::add) }
            }
        }
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-14", "id"))
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        compose
            .onNodeWithContentDescription(formatLocalDate("2026-10-16", "id"))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf<LeaveCreateEvent>(LeaveCreateEvent.DateSelected(16)), events)
        }
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LeaveCreateEvent.DialogDismissed, events.last()) }
        capture("leave-create-calendar-large-text", compose.onNode(isDialog()))
    }

    private fun previewState(): LeaveCreateState {
        val now = Instant.parse("2026-10-12T01:00:00Z")
        val type =
            LeaveTypeOption(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "ANNUAL",
                "Cuti tahunan",
                LocalDate(2026, 1, 1),
                true,
                true,
                false,
                true,
                0,
                0,
            )
        val from = LocalDate(2026, 10, 15)
        val until = LocalDate(2026, 10, 17)
        val form = LeaveCreateForm(from, until, type, LeavePortion.FIRST_HALF, "Keperluan keluarga")
        val access =
            LeaveAccess(
                "session",
                "11111111-1111-4111-8111-111111111111",
                "https://hris.example.test/",
                "22222222-2222-4222-8222-222222222222",
                setOf("leave.self.manage"),
                now + 1.hours,
                now + 1.hours,
                "client.workspace_expired",
            )
        val selected =
            LeaveDraftSelection(
                "33333333-3333-4333-8333-333333333333",
                type.id,
                (15..17).map { LeaveRequestedDay(LocalDate(2026, 10, it), LeavePortion.FIRST_HALF) },
            )
        val preview =
            LeaveRequestPreview(
                selected.employeeId,
                now,
                "Asia/Jakarta",
                LeaveRequestPolicy(type.id, type.code, 0, type.name, true, true, false),
                (15..16).map {
                    LeaveDay(
                        LocalDate(2026, 10, it),
                        LeavePortion.FIRST_HALF,
                        now,
                        now + 8.hours,
                        480,
                        240,
                    )
                },
                LeaveDays(2),
                listOf(until),
                listOf(
                    LeavePreviewBalance(
                        2026,
                        LeaveDays(25),
                        LeaveDays(0),
                        LeaveDays(0),
                        LeaveDays(2),
                        LeaveDays(23),
                        0,
                    )
                ),
                "a".repeat(64),
            )
        return LeaveCreateState(
            form = form,
            catalog = LeavePage(listOf(type)),
            commandReady = true,
            preview = LeavePreparation(access, selected, preview, now, now + 60.seconds),
        )
    }

    private fun capture(name: String, node: SemanticsNodeInteraction) {
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/$name.png").writeBytes(data.bytes)
            }
        }
    }
}
