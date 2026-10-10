package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

@Composable
fun AppBrand(modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(38.dp).background(Color(0xFF0067B8), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AppText("HR", style = FluentTheme.typography.bodyStrong, color = Color.White)
        }
        AppText("HRIS", style = FluentTheme.typography.subtitle, color = AppColors.text)
    }
}
