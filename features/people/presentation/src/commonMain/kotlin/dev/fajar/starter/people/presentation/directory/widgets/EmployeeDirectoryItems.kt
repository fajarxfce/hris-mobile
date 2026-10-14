package dev.fajar.starter.people.presentation.directory.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.directory.*

/** Shared list rendering for browsing and a route-owned search. */
fun LazyListScope.employeeDirectoryItems(state: EmployeesState, onEvent: (EmployeesEvent) -> Unit) {
    if (state.loading && state.page == null) item(key = "progress") { AppLoading() }
    if (state.syncPending && state.page == null && state.failure == null && !state.loading)
        item(key = "sync-pending") { AppText(appString(AppString.SyncPending)) }
    if (state.failure != null)
        item(key = "failure") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFeedback(failureText(requireNotNull(state.failure)))
                if (state.page != null)
                    AppText(appString(AppString.DirectorySaved), color = AppColors.muted)
                if (state.needsVerification)
                    AppButton(
                        appString(AppString.VerifyAccount),
                        { onEvent(EmployeesEvent.VerificationRequested) },
                    )
                else
                    AppTextButton(
                        appString(AppString.Retry),
                        { onEvent(EmployeesEvent.RefreshRequested) },
                        enabled = !state.syncing && !state.loading,
                    )
            }
        }
    if (state.page?.items?.isEmpty() == true && !state.loading)
        item(key = "empty") {
            AppText(
                appString(AppString.EmployeesEmpty),
                Modifier.padding(vertical = 24.dp),
                color = AppColors.muted,
            )
        }
    items(state.page?.items.orEmpty(), key = { it.id }, contentType = { "employee" }) {
        EmployeeRow(it)
    }
    if (state.after != null || state.page?.nextCursor != null)
        item(key = "pages") {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (state.after != null)
                    AppTextButton(
                        appString(AppString.DirectoryFirstPage),
                        { onEvent(EmployeesEvent.PageRequested(null)) },
                        enabled = !state.loading,
                    )
                if (state.page?.nextCursor != null)
                    AppTextButton(
                        appString(AppString.DirectoryNextPage),
                        { onEvent(EmployeesEvent.PageRequested(state.page.nextCursor)) },
                        enabled = !state.loading,
                    )
            }
        }
}
