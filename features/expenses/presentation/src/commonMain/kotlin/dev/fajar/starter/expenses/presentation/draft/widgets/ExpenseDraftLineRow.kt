package dev.fajar.starter.expenses.presentation.draft.widgets

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.ExpenseLine
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Delete

@Composable
fun ExpenseDraftLineRow(
    line: ExpenseLine,
    category: String?,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    AppListRow(
        line.description,
        onEdit,
        supportingText = formatLocalDate(line.occurredOn.toString(), appLanguageTag()),
        metadata = {
            AppText("IDR " + formatDecimal(line.amount, appLanguageTag()))
            if (category != null) AppText(category, color = AppColors.muted)
        },
        enabled = enabled,
        trailing = {
            AppIconButton(
                Icons.Regular.Delete,
                appString(AppString.ExpenseRemoveItem, line.description),
                onRemove,
                enabled = enabled,
            )
        },
    )
}
