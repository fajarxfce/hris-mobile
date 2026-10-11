package dev.fajar.starter.leave.presentation.detail.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(appString(change.kind.label()), style = FluentTheme.typography.bodyStrong)
            AppBadge(appString(change.status.label()))
        }
        if (change.reason.isNotBlank()) AppText(change.reason)
        AppText(
            formatDate(change.recordedAt.toEpochMilliseconds(), appLanguageTag()),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
    }
}
