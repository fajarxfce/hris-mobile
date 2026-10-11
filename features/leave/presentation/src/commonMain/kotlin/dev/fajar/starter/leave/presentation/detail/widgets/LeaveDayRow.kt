package dev.fajar.starter.leave.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveDay
import dev.fajar.starter.leave.presentation.formatters.label
import dev.fajar.starter.localization.*

@Composable
fun LeaveDayRow(day: LeaveDay) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppText(formatLocalDate(day.date.toString(), appLanguageTag()))
        AppText(appString(day.portion.label()), color = AppColors.muted)
    }
}
