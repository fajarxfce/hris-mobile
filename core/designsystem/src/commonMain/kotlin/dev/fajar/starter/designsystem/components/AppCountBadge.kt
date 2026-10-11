package dev.fajar.starter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme

/** Compact visual count; the caller supplies a localized description with the complete value. */
@Composable
fun AppCountBadge(count: Long, description: String, modifier: Modifier = Modifier) {
    if (count > 0) {
        Box(
            modifier
                .clearAndSetSemantics { contentDescription = description }
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                .background(AppColors.text, CircleShape)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(
                if (count > 99) "99+" else count.toString(),
                style =
                    FluentTheme.typography.caption.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = AppColors.surface,
                maxLines = 1,
            )
        }
    }
}
