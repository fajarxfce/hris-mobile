package dev.fajar.starter.collaboration.presentation.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

/** A named conversation/participant action. The parent owns placement and list spacing. */
@Composable
fun ChatPersonRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .background(AppColors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val initials =
            name
                .split(Regex("\\s+"))
                .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
                .take(2)
                .joinToString("")
                .uppercase()
        Box(
            Modifier.size(44.dp).background(AppColors.tint, CircleShape).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            AppText(initials, style = FluentTheme.typography.bodyStrong, color = AppColors.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (trailing != null && maxWidth < 180.dp * LocalDensity.current.fontScale) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PersonName(name)
                        trailing()
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PersonName(name, Modifier.weight(1f))
                        trailing?.invoke()
                    }
                }
            }
            supporting?.invoke()
        }
    }
}

@Composable
private fun PersonName(name: String, modifier: Modifier = Modifier) {
    AppText(
        name,
        modifier,
        style = FluentTheme.typography.bodyStrong,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
