package dev.fajar.starter.leave.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveRequestSummary
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun LeaveRequestRow(request: LeaveRequestSummary, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        AppListRow(
            title = request.typeName,
            onClick = onClick,
            supportingText = leaveDateRange(request.from, request.until),
            metadata = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppBadge(appString(request.status.label()), tone = request.status.badgeTone())
                    AppText(leaveDaysText(request.charged), color = AppColors.muted)
                }
            },
            trailing = { AppIcon(Icons.Regular.ChevronRight) },
        )
    }
}
