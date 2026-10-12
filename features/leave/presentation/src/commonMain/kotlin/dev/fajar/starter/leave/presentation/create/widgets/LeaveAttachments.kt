package dev.fajar.starter.leave.presentation.create.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.policies.MAX_LEAVE_ATTACHMENTS
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Dismiss

@Composable
fun LeaveAttachments(state: LeaveCreateState, onEvent: (LeaveCreateEvent) -> Unit) {
    val form = state.form ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(appString(AppString.LeaveAttachments), style = FluentTheme.typography.bodyStrong)
        AppText(
            appString(AppString.LeaveAttachmentLimit, MAX_LEAVE_ATTACHMENTS.toString()),
            color = AppColors.muted,
        )
        form.attachments.forEach { document ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    AppText(
                        document.fileName,
                        color = AppColors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AppIconButton(
                    Icons.Regular.Dismiss,
                    appString(AppString.LeaveRemoveAttachment, document.fileName),
                    { onEvent(LeaveCreateEvent.AttachmentRemoved(document.revisionId)) },
                    enabled = state.editable && state.evidenceTarget == null,
                )
            }
        }
        AppListRow(
            appString(AppString.LeaveAddAttachment),
            { onEvent(LeaveCreateEvent.EvidencePickerRequested) },
            enabled = state.canAddAttachment,
            trailing = { AppIcon(Icons.Regular.ChevronRight) },
        )
    }
}
