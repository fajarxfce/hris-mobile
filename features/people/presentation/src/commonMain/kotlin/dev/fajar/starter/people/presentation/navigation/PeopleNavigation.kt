package dev.fajar.starter.people.presentation.navigation

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.people.presentation.profile.*
import dev.fajar.starter.people.presentation.profile.pages.ProfilePage
import dev.fajar.starter.presentation.mvi.CollectEffects
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

fun NavGraphBuilder.peopleRoutes(workspaceId: String, onVerify: () -> Unit, onBack: () -> Unit) {
    composable<ProfileRoute> {
        val viewModel = koinViewModel<ProfileViewModel>(parameters = { parametersOf(workspaceId) })
        val state by viewModel.state.collectAsStateWithLifecycle()
        val employmentList = rememberLazyListState()
        LifecycleStartEffect(viewModel) {
            viewModel.onEvent(ProfileEvent.Entered)
            onStopOrDispose { viewModel.onEvent(ProfileEvent.Stopped) }
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                ProfileEffect.Back -> onBack()
                ProfileEffect.VerifyAccount -> onVerify()
            }
        }
        ProfilePage(state, employmentList, viewModel::onEvent)
    }
}
