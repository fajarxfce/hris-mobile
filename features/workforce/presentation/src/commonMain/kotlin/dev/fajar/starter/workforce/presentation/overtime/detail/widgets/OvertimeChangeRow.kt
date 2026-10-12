package dev.fajar.starter.workforce.presentation.overtime.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeChange
import dev.fajar.starter.workforce.presentation.overtime.formatters.label
import io.github.composefluent.FluentTheme

@Composable
fun OvertimeChangeRow(change: OvertimeChange) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppText(appString(change.kind.label()), style = FluentTheme.typography.bodyStrong)
        AppText(appString(change.status.label()), color = AppColors.muted)
        if (change.reason.isNotBlank()) AppText(change.reason)
        AppText(
            formatDate(change.recordedAt.toEpochMilliseconds(), appLanguageTag()),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
    }
}
