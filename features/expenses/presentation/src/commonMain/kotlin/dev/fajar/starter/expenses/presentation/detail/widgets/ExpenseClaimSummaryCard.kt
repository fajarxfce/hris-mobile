package dev.fajar.starter.expenses.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.ExpenseClaim
import dev.fajar.starter.expenses.presentation.formatters.badgeTone
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun ExpenseClaimSummaryCard(claim: ExpenseClaim) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
        AppHeading(claim.draft.title, style = FluentTheme.typography.subtitle)
        AppBadge(appString(claim.status.label()), tone = claim.status.badgeTone())
        AppText(
            claim.draft.employeeName + " · " + claim.draft.employeeNumber,
            color = AppColors.muted,
        )
        AppText(
            appString(AppString.ExpenseTotal),
            Modifier.padding(top = 12.dp),
            color = AppColors.muted,
        )
        AppText(
            claim.draft.currency + " " + formatDecimal(claim.draft.total, appLanguageTag()),
            style = FluentTheme.typography.title.copy(fontFeatureSettings = "tnum"),
            color = AppColors.accent,
        )
        AppText(
            appString(
                AppString.ExpenseCreated,
                formatDate(claim.createdAt.toEpochMilliseconds(), appLanguageTag()),
            ),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
    }
}
