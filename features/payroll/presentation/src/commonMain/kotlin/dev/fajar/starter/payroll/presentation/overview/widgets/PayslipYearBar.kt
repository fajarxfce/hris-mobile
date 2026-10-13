package dev.fajar.starter.payroll.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.presentation.overview.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun PayslipYearBar(state: PayslipsState, onEvent: (PayslipsEvent) -> Unit) {
    val year = state.filters?.year ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AppIconButton(
            Icons.Regular.ChevronLeft,
            appString(AppString.PreviousYear),
            { onEvent(PayslipsEvent.PreviousYearRequested) },
            enabled = !state.readRestricted && year > 2024,
        )
        AppText(year.toString(), style = FluentTheme.typography.bodyStrong)
        AppIconButton(
            Icons.Regular.ChevronRight,
            appString(AppString.NextYear),
            { onEvent(PayslipsEvent.NextYearRequested) },
            enabled = !state.readRestricted && year < 2100,
        )
    }
}
