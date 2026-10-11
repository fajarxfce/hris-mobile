package dev.fajar.starter.leave.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveBalance
import dev.fajar.starter.leave.presentation.overview.formatters.leaveDaysText
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveBalanceCard(balance: LeaveBalance) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(balance.typeName, style = FluentTheme.typography.bodyStrong)
                if (balance.closed) AppBadge(appString(AppString.LeaveClosedYear))
            }
            Column(
                Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText(appString(AppString.LeaveAvailable), color = AppColors.muted)
                AppText(
                    leaveDaysText(balance.available),
                    style = FluentTheme.typography.title,
                    color = AppColors.accent,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.widthIn(min = 120.dp).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText(
                        appString(AppString.LeaveReserved),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                    AppText(leaveDaysText(balance.reserved))
                }
                Column(
                    Modifier.widthIn(min = 120.dp).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText(
                        appString(AppString.LeaveConsumed),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                    AppText(leaveDaysText(balance.consumed))
                }
            }
        }
    }
}
