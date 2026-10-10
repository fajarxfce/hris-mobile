package dev.fajar.starter.workspace.presentation.selection.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workspace.presentation.selection.*

@Composable
fun WorkspacePage(state: WorkspaceState, onEvent: (WorkspaceEvent) -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 24.dp),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item("header") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppBrand()
                    AppHeading(appString(AppString.ChooseCompany))
                    AppText(appString(AppString.ChooseCompanyDescription), color = AppColors.muted)
                }
            }
            if (state.loading) item("loading") { AppLoading() }
            if (state.failure != null)
                item("failure") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppFeedback(failureText(state.failure))
                        AppButton(
                            appString(AppString.TryAgain),
                            { onEvent(WorkspaceEvent.CompaniesRequested) },
                            enabled = !state.loading && state.selectingId == null,
                        )
                    }
                }
            if (!state.loading && state.failure == null && state.companies.isEmpty())
                item("empty") { AppText(appString(AppString.NoCompanies)) }
            items(state.companies, key = { it.id }) { company ->
                AppCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppHeading(company.name)
                        AppText(company.code, color = AppColors.muted)
                        AppButton(
                            appString(AppString.OpenCompany),
                            { onEvent(WorkspaceEvent.CompanySelected(company.id)) },
                            Modifier.fillMaxWidth(),
                            primary = false,
                            enabled = !state.loading && state.selectingId == null,
                            loading = state.selectingId == company.id,
                        )
                    }
                }
            }
            item("sign-out") {
                AppButton(
                    appString(AppString.SignOut),
                    { onEvent(WorkspaceEvent.SignOutRequested) },
                    primary = false,
                    enabled = !state.loading && state.selectingId == null,
                )
            }
        }
    }
}
