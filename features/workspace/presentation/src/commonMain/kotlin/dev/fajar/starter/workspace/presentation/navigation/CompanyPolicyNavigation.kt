package dev.fajar.starter.workspace.presentation.navigation

import androidx.compose.runtime.State
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.workspace.presentation.policy.*
import dev.fajar.starter.workspace.presentation.policy.pages.CompanyPolicyPage

fun NavGraphBuilder.companyPolicyRoutes(
    state: State<CompanyPolicyState>,
    onEvent: (CompanyPolicyEvent) -> Unit,
) {
    composable<CompanyPolicyRoute> { CompanyPolicyPage(state.value, onEvent) }
}
