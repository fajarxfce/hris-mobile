package dev.fajar.starter.leave.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.leave.presentation.overview.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveYearBar(year: Int, enabled: Boolean, onEvent: (LeaveEvent) -> Unit) {

    AppPeriodNavigation(
        appString(AppString.PreviousYear),
        appString(AppString.NextYear),
        { onEvent(LeaveEvent.YearRequested(year - 1)) },
        { onEvent(LeaveEvent.YearRequested(year + 1)) },
        Modifier.fillMaxWidth(),
        previousEnabled = enabled && year > 1900,
        nextEnabled = enabled && year < 2200,
    ) {
        AppText(year.toString(), style = FluentTheme.typography.bodyStrong)
    }
}
