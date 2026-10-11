package dev.fajar.starter.workforce.presentation.attendance.history.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceSummary
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import io.github.composefluent.FluentTheme

@Composable
fun AttendanceMetrics(summary: AttendanceSummary) {
    val values =
        listOf(
            AppString.AttendancePresent to
                formatNumber(summary.presentDays.toLong(), appLanguageTag()),
            AppString.AttendanceAbsent to
                formatNumber(summary.absentDays.toLong(), appLanguageTag()),
            AppString.AttendanceNeedsReview to
                formatNumber(summary.reviewDays.toLong(), appLanguageTag()),
            AppString.AttendanceAcceptedHours to calendarDuration(summary.acceptedMinutes),
        )
    val minimumWidth = 136.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = ((maxWidth + 16.dp) / (minimumWidth + 16.dp)).toInt().coerceIn(1, 2)
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                values.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        row.forEach { (label, value) ->
                            key(label) {
                                Column(
                                    Modifier.weight(1f).semantics(mergeDescendants = true) {},
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    AppText(appString(label), color = AppColors.muted)
                                    AppText(value, style = FluentTheme.typography.title)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
