package dev.fajar.starter.workspace.presentation.selection.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workspace.presentation.selection.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Building
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun WorkspacePage(state: WorkspaceState, onEvent: (WorkspaceEvent) -> Unit) {
    AppLazyPage(
        maxWidth = 600.dp,
        spacing = 12.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = { AppTopBar(appString(AppString.ChooseCompany)) },
        bottomBar = {
            AppActionBar {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (state.signingOut) AppProgressRing(appString(AppString.SigningOut))
                    AppTextButton(
                        appString(
                            if (state.signingOut) AppString.SigningOut else AppString.SignOut
                        ),
                        { onEvent(WorkspaceEvent.SignOutRequested) },
                        enabled = !state.busy,
                    )
                }
            }
        },
    ) {
        item("description") {
            AppText(appString(AppString.ChooseCompanyDescription), color = AppColors.muted)
        }
        if (state.loading) item("loading") { AppLoading(compact = state.companies.isNotEmpty()) }
        if (state.failure != null)
            item("failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(state.failure))
                    AppTextButton(
                        appString(AppString.TryAgain),
                        { onEvent(WorkspaceEvent.CompaniesRequested) },
                        enabled = !state.busy,
                    )
                }
            }
        if (!state.loading && state.failure == null && state.companies.isEmpty())
            item("empty") { AppText(appString(AppString.NoCompanies)) }
        items(state.companies, key = { it.id }) { company ->
            val opening = state.selectingId == company.id
            val progress = appString(AppString.OpeningCompany)
            AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                AppListRow(
                    title = company.name,
                    supportingText = company.code,
                    emphasized = true,
                    onClick = { onEvent(WorkspaceEvent.CompanySelected(company.id)) },
                    enabled = !state.busy,
                    modifier =
                        Modifier.semantics {
                            if (opening) {
                                stateDescription = progress
                                liveRegion = LiveRegionMode.Polite
                            }
                        },
                    leading = {
                        Box(
                            Modifier.size(40.dp)
                                .background(AppColors.tint, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(Icons.Regular.Building, tint = AppColors.accent)
                        }
                    },
                    metadata = { if (opening) AppText(progress, color = AppColors.accent) },
                    trailing = {
                        if (opening) AppProgressRing(progress)
                        else AppIcon(Icons.Regular.ChevronRight, tint = AppColors.muted)
                    },
                )
            }
        }
    }
}
