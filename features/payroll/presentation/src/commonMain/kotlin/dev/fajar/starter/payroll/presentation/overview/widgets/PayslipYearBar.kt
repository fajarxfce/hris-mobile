package dev.fajar.starter.payroll.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.presentation.overview.*
import io.github.composefluent.FluentTheme

@Composable
fun PayslipYearBar(state: PayslipsState, onEvent: (PayslipsEvent) -> Unit) {
    val year = state.filters?.year ?: return
    val enabled = !state.readRestricted
    AppPeriodNavigation(
        appString(AppString.PreviousYear),
        appString(AppString.NextYear),
        { onEvent(PayslipsEvent.PreviousYearRequested) },
        { onEvent(PayslipsEvent.NextYearRequested) },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        previousEnabled = enabled && year > 2024,
        nextEnabled = enabled && year < 2100,
    ) {
        AppText(year.toString(), style = FluentTheme.typography.bodyStrong)
    }
}
