package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors

@Composable
fun AppIcon(
    icon: ImageVector,
    description: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.muted,
) {
    Image(icon, description, modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
}
