package dev.fajar.starter.expenses.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun ExpenseApprovalCard(submission: ExpenseSubmission) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
        AppHeading(appString(AppString.ExpenseApproval), style = FluentTheme.typography.bodyStrong)
        AppBadge(appString(submission.approval.status.label()))
        if (
            submission.approval.status == ExpenseApprovalStatus.PENDING ||
                submission.approval.status == ExpenseApprovalStatus.BLOCKED
        )
            AppText(
                appString(
                    AppString.ExpenseApprovalStep,
                    (submission.approval.currentStep + 1).toString(),
                    submission.approval.stages.size.toString(),
                )
            )
        AppText(
            appString(
                AppString.ExpenseSubmitted,
                formatDate(submission.submittedAt.toEpochMilliseconds(), appLanguageTag()),
            ),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
        submission.reviews.forEach { review ->
            AppText(
                appString(
                    AppString.ExpenseReviewStep,
                    (review.step + 1).toString(),
                    appString(review.decision.label()),
                ),
                Modifier.padding(top = 8.dp),
                style = FluentTheme.typography.bodyStrong,
            )
            if (review.reason.isNotBlank()) AppText(review.reason)
            AppText(
                formatDate(review.decidedAt.toEpochMilliseconds(), appLanguageTag()),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
        }
    }
}
