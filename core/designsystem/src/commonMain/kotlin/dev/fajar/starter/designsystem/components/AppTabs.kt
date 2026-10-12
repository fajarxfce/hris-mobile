package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

/** Controlled content categories; selection and data acquisition belong to the caller. */
@Composable
fun <T> AppTabs(
    items: List<AppTabItem<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
        items.forEach { item ->
            Column(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .alpha(if (enabled) 1f else 0.4f)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .selectable(
                        selected = item.value == selected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { onSelected(item.value) },
                    )
            ) {
                Box(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        item.label,
                        style = FluentTheme.typography.bodyStrong,
                        textAlign = TextAlign.Center,
                        color = if (item.value == selected) AppColors.accent else AppColors.muted,
                    )
                }
                Box(
                    Modifier.fillMaxWidth()
                        .height(2.dp)
                        .background(
                            if (item.value == selected) AppColors.accent else Color.Transparent
                        )
                )
            }
        }
    }
}
