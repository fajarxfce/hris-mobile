package dev.fajar.starter.expenses.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.ExpenseChange
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun ExpenseChangeRow(change: ExpenseChange) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
