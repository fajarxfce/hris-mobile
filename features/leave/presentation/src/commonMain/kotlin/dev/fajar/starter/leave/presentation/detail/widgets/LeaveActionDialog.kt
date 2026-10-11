package dev.fajar.starter.leave.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.leave.presentation.detail.*
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.localization.*

@Composable
fun LeaveActionDialog(
    form: LeaveActionForm,
    working: Boolean,
    onEvent: (LeaveRequestEvent) -> Unit,
) {
    AppDialog(
        title = appString(leaveActionLabel(form.action)),
        closeLabel = appString(AppString.Close),
        onDismiss = { onEvent(LeaveRequestEvent.ActionDismissed) },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppText(appString(leaveActionPrompt(form.action)))
            AppTextField(
                label = appString(AppString.LeaveReason),
                value = form.reason,
                onValueChange = { onEvent(LeaveRequestEvent.ReasonChanged(it)) },
                enabled = !working,
                error =
                    form.failure
                        ?.takeIf { it.kind == FailureKind.Validation }
                        ?.let { failureText(it) },
                maxLines = 4,
            )
            form.failure
                ?.takeUnless { it.kind == FailureKind.Validation }
                ?.let { AppFeedback(failureText(it)) }
            AppButton(
                appString(leaveActionLabel(form.action)),
                { onEvent(LeaveRequestEvent.ActionConfirmed) },
                Modifier.fillMaxWidth(),
                loading = working,
            )
        }
    }
}
