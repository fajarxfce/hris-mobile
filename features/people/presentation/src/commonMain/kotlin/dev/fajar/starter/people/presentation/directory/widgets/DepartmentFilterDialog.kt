package dev.fajar.starter.people.presentation.directory.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.directory.*

@Composable
fun DepartmentFilterDialog(
    state: DepartmentFilterState,
    selected: String?,
    onEvent: (EmployeesEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.Department),
        appString(AppString.Close),
        { onEvent(EmployeesEvent.FilterDismissed) },
    ) {
        AppSearchField(
            state.query,
            { onEvent(EmployeesEvent.DepartmentQueryChanged(it)) },
            appString(AppString.DepartmentSearch),
            appString(AppString.ClearSearch),
            Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(
            Modifier.weight(1f, fill = false).selectableGroup(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "all") {
                AppRadioRow(
                    appString(AppString.AllDepartments),
                    selected == null,
                    { onEvent(EmployeesEvent.DepartmentSelected(null)) },
                )
            }
            if (state.loading) item(key = "progress") { AppLoading() }
            if (state.failure != null)
                item(key = "failure") {
                    AppFeedback(failureText(state.failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(EmployeesEvent.VerificationRequested) },
                        )
                    else
                        AppTextButton(
                            appString(AppString.Retry),
                            { onEvent(EmployeesEvent.DepartmentRetryRequested) },
                        )
                }
            items(state.result?.items.orEmpty(), key = { it.id }) {
                AppRadioRow(
                    it.name,
                    it.id == selected,
                    { onEvent(EmployeesEvent.DepartmentSelected(it.id)) },
                )
            }
            if (state.result?.items?.isEmpty() == true && !state.loading)
                item(key = "empty") {
                    AppText(
                        appString(AppString.DepartmentsEmpty),
                        Modifier.padding(12.dp),
                        color = AppColors.muted,
                    )
                }
            if (state.result?.hasMore == true)
                item(key = "refine") {
                    AppText(
                        appString(AppString.RefineDepartmentSearch),
                        Modifier.padding(12.dp),
                        color = AppColors.muted,
                    )
                }
        }
    }
}
