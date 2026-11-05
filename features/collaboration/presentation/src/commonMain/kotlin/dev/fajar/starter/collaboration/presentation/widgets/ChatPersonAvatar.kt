package dev.fajar.starter.collaboration.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
internal fun ChatPersonAvatar(name: String, modifier: Modifier = Modifier) {
    val initials =
        name
            .split(Regex("\\s+"))
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
            .take(2)
            .joinToString("")
            .uppercase()
    Box(
        modifier.size(44.dp).background(AppColors.tint, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        AppText(initials, style = FluentTheme.typography.bodyStrong, color = AppColors.accent)
    }
}
