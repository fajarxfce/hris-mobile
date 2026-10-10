package dev.fajar.starter.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Text

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = FluentTheme.typography.body,
    color: Color = AppColors.text,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    Text(text = text, modifier = modifier, style = style, color = color, textAlign = textAlign)
}
