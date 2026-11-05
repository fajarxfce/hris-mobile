package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.Filter
import io.github.composefluent.icons.regular.Filter

/** Compact disclosure; a selected filter remains visible and has an accessible value. */
@Composable
fun AppFilterButton(
    description: String,
    selected: Boolean,
    valueDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) AppColors.tint else Color.Transparent)
            .semantics {
                this.selected = selected
                stateDescription = valueDescription
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            if (selected) Icons.Filled.Filter else Icons.Regular.Filter,
            description,
            tint = if (selected) AppColors.accent else AppColors.text,
        )
    }
}
