package dev.fajar.starter.leave.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.leave.domain.entities.LeaveStatus
import dev.fajar.starter.leave.presentation.overview.*
import dev.fajar.starter.leave.presentation.overview.formatters.label
import dev.fajar.starter.localization.*

@Composable
fun LeaveFilterDialog(state: LeaveState, onEvent: (LeaveEvent) -> Unit) {
    AppDialog(
        appString(
            if (state.dialog == LeaveDialog.STATUS) AppString.LeaveRequestStatus
            else AppString.ChooseEmployment
        ),
        appString(AppString.Close),
        { onEvent(LeaveEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading || state.refreshing) item(key = "loading") { AppLoading() }
            if (state.dialog == LeaveDialog.STATUS) {
                item(key = "all") {
                    AppRadioRow(
                        appString(AppString.LeaveAllStatuses),
                        state.filters?.status == null,
                        { onEvent(LeaveEvent.StatusSelected(null)) },
                    )
                }
                items(LeaveStatus.entries, key = { it.name }) { status ->
                    AppRadioRow(
                        appString(status.label()),
                        state.filters?.status == status,
                        { onEvent(LeaveEvent.StatusSelected(status)) },
                    )
                }
            } else {
                val content = state.content
                val failure = state.refreshFailure ?: state.cacheFailure
                if (failure != null) item(key = "failure") { AppFeedback(failureText(failure)) }
                items(content?.directory?.items.orEmpty(), key = { it.id }) { employee ->
                    AppRadioRow(
                        employee.employeeNumber,
                        employee.id == content?.selection?.employeeId,
                        { onEvent(LeaveEvent.EmploymentSelected(employee.id)) },
                    )
                }
                if (content?.directory?.nextCursor != null)
                    item(key = "next") {
                        AppTextButton(
                            appString(AppString.MoreEmployments),
                            {
                                onEvent(
                                    LeaveEvent.EmploymentPageRequested(content.directory.nextCursor)
                                )
                            },
                        )
                    }
                if (content?.selection?.employmentAfter != null)
                    item(key = "first") {
                        AppTextButton(
                            appString(AppString.CalendarFirstEmployments),
                            { onEvent(LeaveEvent.EmploymentPageRequested(null)) },
                        )
                    }
            }
        }
    }
}
