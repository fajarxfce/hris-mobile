package dev.fajar.starter.people.presentation.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.people.presentation.directory.*
import dev.fajar.starter.people.presentation.directory.pages.EmployeeSearchPage
import dev.fajar.starter.people.presentation.directory.pages.EmployeesPage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.employeesRoutes(
    workspaceId: String,
    onVerify: () -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) {
    composable<EmployeesRoute> { EmployeesContent(workspaceId, onVerify, onSearch, onBack) }
    composable<EmployeeSearchRoute> {
        EmployeeDirectoryEntry(workspaceId, onVerify, onSearch, onBack) { state, list, onEvent ->
            EmployeeSearchPage(state, list, onEvent)
        }
    }
}

/** Used by the Home tab and the standalone route, each with its own navigation owner. */
@Composable
fun EmployeesContent(
    workspaceId: String,
    onVerify: () -> Unit,
    onSearch: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    EmployeeDirectoryEntry(workspaceId, onVerify, onSearch, onBack) { state, list, onEvent ->
        EmployeesPage(state, list, showBack = onBack != null, onEvent)
    }
}

/** Each navigation entry owns its own directory state, effects and acquisition lifetime. */
@Composable
private fun EmployeeDirectoryEntry(
    workspaceId: String,
    onVerify: () -> Unit,
    onSearch: () -> Unit,
    onBack: (() -> Unit)?,
    content: @Composable (EmployeesState, LazyListState, (EmployeesEvent) -> Unit) -> Unit,
) {
    val viewModel = koinViewModel<EmployeesViewModel>(parameters = { parametersOf(workspaceId) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(EmployeesEvent.Entered)
        onStopOrDispose { viewModel.onEvent(EmployeesEvent.Stopped) }
    }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            EmployeesEffect.OpenSearch -> onSearch()
            EmployeesEffect.Back -> onBack?.invoke()
            EmployeesEffect.ScrollToStart -> list.requestScrollToItem(0)
            EmployeesEffect.VerifyAccount -> onVerify()
        }
    }
    content(state, list, viewModel::onEvent)
}
