package dev.fajar.starter.collaboration.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CheckmarkCircle
import io.github.composefluent.icons.regular.Circle

/** The checkbox keeps its own column; large names wrap without moving the selection control. */
@Composable
internal fun ChatForwardRecipientRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .background(if (selected) AppColors.tint else AppColors.surface)
            .toggleable(
                selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatPersonAvatar(name)
        AppText(
            name,
            Modifier.weight(1f),
            style = FluentTheme.typography.bodyStrong,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        AppIcon(
            if (selected) Icons.Regular.CheckmarkCircle else Icons.Regular.Circle,
            null,
            Modifier.size(24.dp),
            tint = if (selected) AppColors.accent else AppColors.muted,
        )
    }
}
