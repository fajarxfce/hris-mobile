package dev.fajar.starter.dashboard.presentation.home.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppCard
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier, padding = 16.dp) {
        AppText(label, style = FluentTheme.typography.caption, color = AppColors.muted)
        Spacer(Modifier.height(12.dp))
        AppText(value, style = FluentTheme.typography.title)
    }
}
