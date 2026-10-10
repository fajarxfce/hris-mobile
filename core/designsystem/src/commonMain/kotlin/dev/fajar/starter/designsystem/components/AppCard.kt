package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = AppColors.surface,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(color, RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.border, RoundedCornerShape(16.dp))
            .padding(padding),
        content = content,
    )
}
