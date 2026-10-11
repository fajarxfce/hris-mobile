package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** A small menu inside its page's scroll container; larger text reduces the column count. */
@Composable
fun <T> AppMenuGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val minimumWidth = 72.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 8.dp) / (minimumWidth + 8.dp)).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        key(itemKey(item)) { Box(Modifier.weight(1f)) { content(item) } }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
