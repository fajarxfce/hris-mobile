package dev.fajar.starter.people.presentation.directory.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.directory.*
import dev.fajar.starter.people.presentation.directory.widgets.DepartmentFilterDialog
import dev.fajar.starter.people.presentation.directory.widgets.employeeDirectoryItems
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun EmployeeSearchPage(
    state: EmployeesState,
    list: LazyListState,
    onEvent: (EmployeesEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 0.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.Back),
                    { onEvent(EmployeesEvent.BackRequested) },
                )
                AppSearchField(
                    state.query,
                    { onEvent(EmployeesEvent.QueryChanged(it)) },
                    appString(AppString.EmployeeSearch),
                    appString(AppString.ClearSearch),
                    Modifier.weight(1f).padding(end = 8.dp),
                    autoFocus = true,
                )
            }
        },
    ) {
        item(key = "filter") {
            Box(Modifier.padding(bottom = 16.dp)) {
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
