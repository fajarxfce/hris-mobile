package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun AppActionTile(
    title: String,
    supportingText: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.surface)
            .border(1.dp, AppColors.border, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(AppColors.tint, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, tint = AppColors.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(
                title,
                style = FluentTheme.typography.bodyStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                supportingText,
                color = AppColors.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppIcon(Icons.Regular.ChevronRight, modifier = Modifier.size(16.dp))
    }
}
