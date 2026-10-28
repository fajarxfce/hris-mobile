package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.presentation.attendance.*
import dev.fajar.starter.workforce.presentation.attendance.widgets.AttendanceHomeCard
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.*
import org.junit.Rule
import org.junit.Test

class AttendanceCaptureLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun acquiredWorkAndRestDaysKeepTheMenuInPlace() = checkSchedule(AppLanguage.English, 1f)

    @Test
    fun largeIndonesianScheduleKeepsTheMenuInPlace() = checkSchedule(AppLanguage.Indonesian, 2f)

    @Test
    fun wrappedIndonesianScheduleKeepsTheMenuInPlace() =
        checkSchedule(AppLanguage.Indonesian, 2f, 280.dp)

    private fun checkSchedule(language: AppLanguage, scale: Float, width: Dp = 360.dp) {
        var state by mutableStateOf(AttendanceState())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, scale),
                LocalAppLanguage provides language,
            ) {
                AppTheme(reduceMotion = true) {
                    Column(Modifier.width(width).padding(16.dp)) {
                        AttendanceHomeCard(state) {}
                        AppText("Services", Modifier.testTag("menu"))
                    }
                }
            }
        }
        val menu = compose.onNodeWithTag("menu")
        val pending = menu.fetchSemanticsNode().boundsInRoot.top
        val date = LocalDate(2026, 10, 19)
        val work =
            WorkDay.Work(
                date,
                CalendarShift(
                    "shift",
                    1,
                    "NIGHT",
                    "Night",
                    LocalTime(22, 0),
                    LocalTime(6, 0),
                    0,
                    "Asia/Jakarta",
                    CalendarWorkMode.ONSITE,
                ),
                Instant.parse("2026-10-19T15:00:00Z"),
                Instant.parse("2026-10-19T23:00:00Z"),
                480,
                CalendarOrigin.PATTERN,
                1,
            )
        for (day in
            listOf(
                work,
                WorkDay.Off(date, CalendarOrigin.PATTERN, 1, null),
                WorkDay.Unassigned(date),
            )) {
            compose.runOnIdle { state = loaded(day) }
            assertEquals(
                pending,
                menu.fetchSemanticsNode().boundsInRoot.top,
                "Schedule ${day::class.simpleName}, width=$width, scale=$scale",
            )
        }
        compose.onAllNodesWithText("00:00 – 00:00 (+1 hari)").assertCountEquals(0)
        compose.onAllNodesWithText("00:00 – 00:00 (+1 day)").assertCountEquals(0)
    }

    private fun loaded(day: WorkDay): AttendanceState {
        val now = Instant.parse("2026-10-19T04:00:00Z")
        val access =
            WorkforceAccess(
                "session",
                "account",
                "audience",
                "company",
                setOf("attendance.self.record"),
                now,
                now,
                "expired",
            )
        val employee = WorkforceEmployment("employee", "EMP-1", WorkforceEmploymentStatus.ACTIVE, 1)
        val period = CalendarPeriod(day.date, day.date)
        val snapshot =
            AttendanceSnapshot(
                access,
                "generation",
                WorkforceDirectory(
                    "account",
                    "company",
                    day.date,
                    "Asia/Jakarta",
                    listOf(employee),
                    null,
                ),
                employee.id,
                period,
                WorkCalendar(1, listOf(day)),
                emptyList(),
                now,
                now,
            )
        return AttendanceState(
            content = AttendanceView(snapshot, emptyList()).toContent(now),
            loading = false,
        )
    }
}
