package dev.fajar.starter.leave.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveCarryBalance
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveCarryBalanceDetails(carry: LeaveCarryBalance) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppText(appString(AppString.LeaveCarryover), style = FluentTheme.typography.bodyStrong)
        AppText(
            appString(AppString.LeaveCarryAwarded, leaveDaysText(carry.awarded)),
            style = FluentTheme.typography.caption,
            color = AppColors.muted,
        )
        AppText(appString(AppString.LeaveCarryIncluded, leaveDaysText(carry.available)))
        AppText(
            leaveCarryValidityText(carry),
            style = FluentTheme.typography.caption,
            color = AppColors.muted,
        )
        if (carry.expired.halfDays > 0)
            AppText(
                appString(AppString.LeaveCarryExpired, leaveDaysText(carry.expired)),
                style = FluentTheme.typography.caption,
                color = AppColors.muted,
            )
        if (carry.transferred.halfDays > 0)
            AppText(
                appString(AppString.LeaveCarryTransferred, leaveDaysText(carry.transferred)),
                style = FluentTheme.typography.caption,
                color = AppColors.muted,
            )
        if (carry.deducted.halfDays > 0)
            AppText(
                appString(AppString.LeaveCarryDeducted, leaveDaysText(carry.deducted)),
                style = FluentTheme.typography.caption,
                color = AppColors.muted,
            )
    }
}
