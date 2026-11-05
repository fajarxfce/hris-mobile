package dev.fajar.starter.people.presentation.directory.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
    topBar: (@Composable () -> Unit)? = null,
    bottomPadding: Dp = 12.dp,
) {
    AppLazyPage(
        state = listState,
        spacing = 0.dp,
        maxWidth = 720.dp,
        contentPadding =
            PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding),
        topBar =
            topBar
                ?: {
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
            Row(
                Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppSearchEntry(
                    appString(AppString.EmployeeSearch),
                    { onEvent(EmployeesEvent.SearchRequested) },
                    Modifier.weight(1f),
                )
                AppFilterButton(
                    appString(AppString.DepartmentFilter),
                    selected = state.department != null,
                    valueDescription =
                        state.department?.name ?: appString(AppString.AllDepartments),
                    onClick = { onEvent(EmployeesEvent.FilterRequested) },
                )
            }
        }
        employeeDirectoryItems(state, onEvent)
    }
    if (state.filter != null) DepartmentFilterDialog(state.filter, state.department?.id, onEvent)
}
