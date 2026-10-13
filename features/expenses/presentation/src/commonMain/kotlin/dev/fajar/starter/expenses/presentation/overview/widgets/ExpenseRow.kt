package dev.fajar.starter.expenses.presentation.overview.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.ExpenseSummary
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun ExpenseRow(claim: ExpenseSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AppListRow(
            title = claim.title,
            supportingText = formatDate(claim.createdAt.toEpochMilliseconds(), appLanguageTag()),
            emphasized = true,
            onClick = onClick,
            metadata = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppText(
                        claim.currency + " " + formatDecimal(claim.total, appLanguageTag()),
                        style = FluentTheme.typography.bodyStrong.copy(fontFeatureSettings = "tnum"),
                    )
                    AppBadge(appString(claim.status.label()))
                }
            },
            trailing = { AppIcon(Icons.Regular.ChevronRight) },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
    }
}
