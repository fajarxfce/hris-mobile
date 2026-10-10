package dev.fajar.starter.dashboard.presentation.home.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.dashboard.domain.entities.Activity
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Checkmark

@Composable
fun ActivityList(items: List<Activity>) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            items.forEach { activity ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier.size(34.dp).background(AppColors.tint, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(Icons.Regular.Checkmark, tint = AppColors.accent)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppText(activity.title, style = FluentTheme.typography.bodyStrong)
                        AppText(
                            activity.detail,
                            color = AppColors.muted,
                            style = FluentTheme.typography.caption,
                        )
                    }
                    AppText(
                        activity.time,
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
            }
        }
    }
}
