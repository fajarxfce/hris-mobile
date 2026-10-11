package dev.fajar.starter.workforce.presentation.attendance.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.*

@Composable
fun AttendanceEmploymentDialog(state: AttendanceState, onEvent: (AttendanceEvent) -> Unit) {
    AppDialog(
        appString(AppString.ChooseEmployment),
        appString(AppString.Close),
        { onEvent(AttendanceEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.refreshing || state.working) item(key = "loading") { AppLoading() }
            if (state.failure != null) item(key = "failure") { AttendanceFeedback(state, onEvent) }
            val content = state.content
            if (content != null) {
                items(content.snapshot.directory.items, key = { it.id }) { employee ->
                    AppRadioRow(
                        employee.employeeNumber,
                        employee.id == content.employee?.id,
                        { onEvent(AttendanceEvent.EmploymentSelected(employee.id)) },
                        enabled = !state.working,
                    )
                }
                if (content.snapshot.directory.nextCursor != null)
                    item(key = "next") {
                        AppTextButton(
                            appString(AppString.MoreEmployments),
                            {
                                onEvent(
                                    AttendanceEvent.EmploymentPageRequested(
                                        content.snapshot.directory.nextCursor
                                    )
                                )
                            },
                            enabled = !state.working,
                        )
                    }
            }
            if (state.selection.after != null)
                item(key = "first") {
                    AppTextButton(
                        appString(AppString.CalendarFirstEmployments),
                        { onEvent(AttendanceEvent.EmploymentPageRequested(null)) },
                        enabled = !state.working,
                    )
                }
        }
    }
}
