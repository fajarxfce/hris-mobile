package dev.fajar.starter.leave.presentation.overview.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveRequestSummary
import dev.fajar.starter.leave.presentation.overview.formatters.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveRequestRow(request: LeaveRequestSummary) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Column(
            Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(request.typeName, style = FluentTheme.typography.bodyStrong)
                AppBadge(appString(request.status.label()))
            }
            AppText(
                if (request.from == request.until)
                    formatLocalDate(request.from.toString(), appLanguageTag())
                else
                    formatLocalDate(request.from.toString(), appLanguageTag()) +
                        " – " +
                        formatLocalDate(request.until.toString(), appLanguageTag())
            )
            AppText(leaveDaysText(request.charged), color = AppColors.muted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
    }
}
