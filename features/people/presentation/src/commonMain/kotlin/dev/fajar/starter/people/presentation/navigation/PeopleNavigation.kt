package dev.fajar.starter.people.presentation.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            viewModel.onEvent(ProfileEvent.DirectoryRequested())
        }
        CollectEffects(viewModel.effects) { effect ->
            when (effect) {
                ProfileEffect.Back -> onBack()
                ProfileEffect.VerifyAccount -> onVerify()
            }
        }
        ProfilePage(state, viewModel::onEvent)
    }
}
