package dev.fajar.starter.people.presentation.directory.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.people.presentation.directory.*
import dev.fajar.starter.people.presentation.directory.widgets.DepartmentFilterDialog
import dev.fajar.starter.people.presentation.directory.widgets.EmployeeRow
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
                        enabled = !state.loading,
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
                AppSearchField(
                    state.query,
                    { onEvent(EmployeesEvent.QueryChanged(it)) },
                    appString(AppString.EmployeeSearch),
                    appString(AppString.ClearSearch),
                )
                AppFilterChip(
                    state.department?.name ?: appString(AppString.AllDepartments),
                    selected = state.department != null,
                    onClick = { onEvent(EmployeesEvent.FilterRequested) },
                )
            }
        }
        if (state.loading) item(key = "progress") { AppLoading() }
        if (state.failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(state.failure))
                    if (state.page != null)
                        AppText(appString(AppString.DirectorySaved), color = AppColors.muted)
                    if (state.needsVerification)
                        AppButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(EmployeesEvent.VerificationRequested) },
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
                Row(
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
    if (state.filter != null) DepartmentFilterDialog(state.filter, state.department?.id, onEvent)
}
