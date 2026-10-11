package dev.fajar.starter.leave.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.leave.presentation.overview.LeaveEvent
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowRight

@Composable
fun LeaveYearBar(year: Int, enabled: Boolean, onEvent: (LeaveEvent) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(
            Icons.Regular.ArrowLeft,
            appString(AppString.PreviousYear),
            { onEvent(LeaveEvent.YearRequested(year - 1)) },
            enabled = enabled && year > 1900,
        )
        AppText(year.toString(), style = FluentTheme.typography.bodyStrong)
        AppIconButton(
            Icons.Regular.ArrowRight,
            appString(AppString.NextYear),
            { onEvent(LeaveEvent.YearRequested(year + 1)) },
            enabled = enabled && year < 2200,
        )
    }
}
