package dev.fajar.starter.workforce.presentation.overtime.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import dev.fajar.starter.workforce.presentation.overtime.formatters.badgeTone
import dev.fajar.starter.workforce.presentation.overtime.formatters.label
import io.github.composefluent.FluentTheme

@Composable
fun OvertimeRequestSummary(details: OvertimeDetails) {
    val request = details.request
    val approval = details.approval
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppText(
            appString(AppString.OvertimeWorkDate),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
        AppHeading(
            formatLocalDate(request.workDate.toString(), appLanguageTag()),
            style = FluentTheme.typography.subtitle,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppBadge(appString(request.status.label()), tone = request.status.badgeTone())
            AppText(request.employeeNumber, color = AppColors.muted)
        }
        if (request.status == OvertimeStatus.APPROVED)
            AppText(
                appString(
                    AppString.OvertimeApprovedTime,
                    calendarDuration(request.approvedMinutes.toLong()),
                )
            )
        if (approval?.status == OvertimeApprovalStatus.BLOCKED)
            AppFeedback(appString(AppString.OvertimeApprovalBlocked))
        else if (approval?.status == OvertimeApprovalStatus.PENDING)
            AppText(
                appString(
                    AppString.OvertimeApprovalStep,
                    formatNumber(approval.currentStep.toLong() + 1, appLanguageTag()),
                    formatNumber(approval.stageCount.toLong(), appLanguageTag()),
                ),
                color = AppColors.muted,
            )
    }
}
