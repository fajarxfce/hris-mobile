package dev.fajar.starter.leave.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveRequestDetails
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveRequestSummaryCard(request: LeaveRequestDetails) {
    AppCard(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        padding = 16.dp,
        spacing = 8.dp,
    ) {
        AppText(request.policy.name, style = FluentTheme.typography.bodyStrong)
        AppText(
            leaveDateRange(request.days.first().date, request.days.last().date),
            style = FluentTheme.typography.subtitle,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(leaveDaysText(request.charged), style = FluentTheme.typography.bodyStrong)
            AppBadge(appString(request.status.label()))
        }
        AppText(
            request.employeeName + " · " + request.employeeNumber,
            Modifier.padding(top = 4.dp),
            color = AppColors.muted,
        )
        AppText(
            appString(
                AppString.LeaveSubmittedOn,
                formatDate(request.submittedAt.toEpochMilliseconds(), appLanguageTag()),
            ),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
    }
}
