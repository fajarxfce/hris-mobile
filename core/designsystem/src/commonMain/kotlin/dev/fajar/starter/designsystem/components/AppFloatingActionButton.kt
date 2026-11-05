package dev.fajar.starter.designsystem.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.designsystem.theme.LocalAppMotion

@Composable
fun AppFloatingActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    enabled: Boolean = true,
) {
    val duration = LocalAppMotion.current.selectionMillis
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(duration)) + slideInVertically(tween(duration)) { it / 3 },
        exit = fadeOut(tween(duration)) + slideOutVertically(tween(duration)) { it / 3 },
    ) {
        Box(
            Modifier.size(56.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(AppColors.accent)
                .alpha(if (enabled) 1f else 0.4f)
                .clickable(enabled = enabled && visible, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, description, tint = AppColors.onAccent)
        }
    }
}
