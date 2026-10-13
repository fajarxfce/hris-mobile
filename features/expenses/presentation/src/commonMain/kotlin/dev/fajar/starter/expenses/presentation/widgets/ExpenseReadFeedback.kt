package dev.fajar.starter.expenses.presentation.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*

@Composable
fun ExpenseReadFeedback(
    failure: Failure,
    needsVerification: Boolean,
    busy: Boolean,
    onVerify: () -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppFeedback(failureText(failure))
        if (needsVerification)
            AppTextButton(appString(AppString.VerifyAccount), onVerify, enabled = !busy)
    }
}
