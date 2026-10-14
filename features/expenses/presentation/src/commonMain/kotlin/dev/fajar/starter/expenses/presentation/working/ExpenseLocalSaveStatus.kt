package dev.fajar.starter.expenses.presentation.working

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*

@Composable
fun ExpenseLocalSaveStatus(
    value: ExpenseEditPersistence,
    enabled: Boolean,
    onRetry: () -> Unit,
    onReview: () -> Unit,
    reviewLabel: String,
) {
    if (value.failure == null) {
        AppText(
            appString(
                if (value.saved) AppString.ExpenseSavedLocally else AppString.ExpenseSavingLocally
            ),
            color = AppColors.muted,
        )
    } else {
        AppFeedback(failureText(value.failure))
        if (value.failure.code == "client.expense_working_copy_changed")
            AppTextButton(reviewLabel, onReview, enabled = enabled)
        else
            AppTextButton(
                appString(AppString.TryAgain),
                onRetry,
                enabled = enabled && !value.writing,
            )
    }
}
