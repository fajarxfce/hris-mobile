package dev.fajar.starter.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import io.github.composefluent.FluentTheme

@Composable
fun AppHeading(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = FluentTheme.typography.title,
) {
    AppText(text, modifier.semantics { heading() }, style)
}
