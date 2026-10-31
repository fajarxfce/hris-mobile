package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

enum class AppBadgeTone {
    Info,
    Neutral,
    Positive,
    Caution,
    Negative,
}

@Composable
fun AppBadge(label: String, modifier: Modifier = Modifier, tone: AppBadgeTone = AppBadgeTone.Info) {
    val dark = FluentTheme.colors.darkMode
    val (foreground, background) =
        when (tone) {
            AppBadgeTone.Info -> AppColors.accent to AppColors.tint
            AppBadgeTone.Neutral -> AppColors.muted to AppColors.canvas
            AppBadgeTone.Positive ->
                if (dark) Color(0xFF86EFAC) to Color(0xFF143826)
                else Color(0xFF166534) to Color(0xFFDCFCE7)
            AppBadgeTone.Caution ->
                if (dark) Color(0xFFFDE68A) to Color(0xFF392C13)
                else Color(0xFF854D0E) to Color(0xFFFEF3C7)
            AppBadgeTone.Negative ->
                AppColors.error to if (dark) Color(0xFF472723) else Color(0xFFFCEBEA)
        }
    AppText(
        label,
        modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = foreground,
        style = FluentTheme.typography.caption,
    )
}
