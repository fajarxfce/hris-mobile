package dev.fajar.starter.inbox.presentation.list.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.inbox.domain.entities.InboxItem
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun InboxRow(item: InboxItem, enabled: Boolean, onClick: () -> Unit) {
    val unread = appString(AppString.Unread)
    AppListRow(
        title = item.title,
        supportingText = item.body,
        emphasized = item.readAt == null,
        enabled = enabled,
        onClick = onClick,
        leading = {
            if (item.readAt == null)
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(AppColors.accent).semantics {
                        contentDescription = unread
                    }
                )
            else Spacer(Modifier.width(7.dp))
        },
        metadata = {
            AppText(
                formatDate(item.deliveredAt.toEpochMilliseconds(), appLanguageTag()),
                style = FluentTheme.typography.caption,
                color = AppColors.muted,
            )
        },
    )
}
