package dev.fajar.starter.workforce.presentation.attendance.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.*

@Composable
fun AttendanceQueueDialog(state: AttendanceState, onEvent: (AttendanceEvent) -> Unit) {
    AppDialog(
        appString(AppString.AttendancePendingActions),
        appString(AppString.Close),
        { onEvent(AttendanceEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.refreshing || state.working) item(key = "loading") { AppLoading() }
            if (state.failure != null) item(key = "failure") { AttendanceFeedback(state, onEvent) }
            val content = state.content
            if (content != null) {
                items(content.commands, key = { it.command.id }) { record ->
                    AttendanceQueueCard(
                        record,
                        content.snapshot.directory.timezone,
                        state.working,
                        onEvent,
                    )
                }
                if (content.commands.isEmpty())
                    item(key = "empty") {
                        AppText(appString(AppString.AttendanceNoPending), color = AppColors.muted)
                    }
                if (content.commands.size == 50)
                    item(key = "limit") {
                        AppText(appString(AppString.AttendanceQueueLimit), color = AppColors.muted)
                    }
            }
        }
    }
}
