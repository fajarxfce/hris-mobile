package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronDown
import io.github.composefluent.icons.regular.Filter

@Composable
fun AppFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = Icons.Regular.Filter,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(4.dp))
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null)
            AppIcon(
                icon,
                modifier = Modifier.size(18.dp),
                tint = if (selected) AppColors.accent else AppColors.muted,
            )
        AppText(
            label,
            Modifier.weight(1f, fill = false),
            maxLines = maxLines,
            color = if (selected) AppColors.accent else AppColors.text,
        )
        AppIcon(Icons.Regular.ChevronDown, modifier = Modifier.size(16.dp), tint = AppColors.muted)
    }
}
