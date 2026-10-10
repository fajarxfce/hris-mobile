package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
            Canvas(Modifier.size(20.dp)) {
                repeat(2) { x ->
                    repeat(2) { y ->
                        drawRect(
                            Color.White,
                            Offset(size.width * x * 0.56f, size.height * y * 0.56f),
                            Size(size.width * 0.42f, size.height * 0.42f),
                        )
                    }
                }
            }
        }
        AppText("Fluent", style = FluentTheme.typography.subtitle, color = AppColors.text)
    }
}
