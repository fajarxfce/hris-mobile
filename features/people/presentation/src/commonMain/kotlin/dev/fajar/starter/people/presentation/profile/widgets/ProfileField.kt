package dev.fajar.starter.people.presentation.profile.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun ProfileField(label: String, value: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(label, color = AppColors.muted)
        AppText(value ?: "—", style = FluentTheme.typography.bodyStrong)
    }
}
