package dev.fajar.starter.workforce.presentation.overtime.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*

@Composable
fun OvertimeReadFeedback(
    failure: Failure,
    needsVerification: Boolean,
    refreshing: Boolean,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppFeedback(failureText(failure))
        if (needsVerification)
            AppTextButton(appString(AppString.VerifyAccount), onVerify, enabled = !refreshing)
    }
}
