package dev.fajar.starter.people.presentation.directory.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.directory.*
import dev.fajar.starter.people.presentation.directory.widgets.DepartmentFilterDialog
import dev.fajar.starter.people.presentation.directory.widgets.employeeDirectoryItems
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun EmployeesPage(
    state: EmployeesState,
    listState: LazyListState,
    showBack: Boolean,
    onEvent: (EmployeesEvent) -> Unit,
) {
    AppLazyPage(
        state = listState,
        spacing = 0.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                title = appString(AppString.Employees),
                navigation =
                    if (showBack) {
                        {
                            AppIconButton(
                                Icons.Regular.ArrowLeft,
                                appString(AppString.Back),
                                { onEvent(EmployeesEvent.BackRequested) },
                            )
                        }
                    } else null,
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(EmployeesEvent.RefreshRequested) },
                        enabled = !state.syncing,
                        loading = state.syncing,
                    )
                },
            )
        },
    ) {
        item(key = "search") {
            Column(
                Modifier.padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppSearchEntry(
                    appString(AppString.EmployeeSearch),
                    { onEvent(EmployeesEvent.SearchRequested) },
                )
                AppFilterChip(
                    state.department?.name ?: appString(AppString.AllDepartments),
                    selected = state.department != null,
                    onClick = { onEvent(EmployeesEvent.FilterRequested) },
                )
            }
        }
        employeeDirectoryItems(state, onEvent)
    }
    if (state.filter != null) DepartmentFilterDialog(state.filter, state.department?.id, onEvent)
}
