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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Search

/** An accessible entry to a search surface; it does not create an editor or show the keyboard. */
@Composable
fun AppSearchEntry(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.surface)
            .border(1.dp, AppColors.border, RoundedCornerShape(12.dp))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(Icons.Regular.Search)
        AppText(label, Modifier.weight(1f), color = AppColors.muted)
    }
}
