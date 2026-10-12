package dev.fajar.starter.leave.presentation.detail.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveChange
import dev.fajar.starter.leave.presentation.formatters.label
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveChangeRow(change: LeaveChange) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(AppColors.muted, CircleShape))
        Column(
            Modifier.weight(1f).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(appString(change.kind.label()), style = FluentTheme.typography.bodyStrong)
            AppText(
                formatDate(change.recordedAt.toEpochMilliseconds(), appLanguageTag()),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
            AppText(
                appString(change.status.label()),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
            if (change.reason.isNotBlank()) AppText(change.reason)
        }
    }
}
