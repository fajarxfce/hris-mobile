package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

/** One accessible action with bounded text, a generous target, and clipped press feedback. */
@Composable
fun AppListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    metadata: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(
                title,
                style =
                    FluentTheme.typography.body.copy(
                        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal
                    ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null)
                AppText(
                    supportingText,
                    color = AppColors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            metadata?.invoke()
        }
        trailing?.invoke()
    }
}
