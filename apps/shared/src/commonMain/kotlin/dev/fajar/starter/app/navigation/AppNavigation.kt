package dev.fajar.starter.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.fajar.starter.app.bootstrap.*
import dev.fajar.starter.auth.presentation.navigation.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.home.HomeTab
import dev.fajar.starter.home.presentation.navigation.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.onboarding.presentation.navigation.*
import dev.fajar.starter.security.presentation.navigation.AppLockSettings
import dev.fajar.starter.settings.presentation.navigation.*
import dev.fajar.starter.workspace.presentation.navigation.*
import kotlinx.coroutines.flow.Flow
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppNavigation(controller: NavHostController, incomingLinks: Flow<String>) {
    val viewModel = koinViewModel<AppViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stage = state.stage
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(incomingLinks, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            incomingLinks.collect { viewModel.onEvent(AppEvent.LinkReceived(it)) }
        }
    }
    val destination =
        when (stage) {
            AppStage.Onboarding -> OnboardingRoute
            AppStage.SignedOut -> LoginRoute
            is AppStage.SignedIn -> if (stage.workspace == null) VerificationRoute else HomeRoute()
            else -> BootstrapRoute
        }
    val scope =
        when (stage) {
            is AppStage.SignedIn ->
                "account/${stage.sessionId}/${stage.workspace?.id ?: "selection"}"
            AppStage.Onboarding -> "onboarding"
            AppStage.SignedOut -> "signed-out"
            else -> "bootstrap"
        }
    LaunchedEffect(scope, state.pendingLink) {
        val link = state.pendingLink
        if (stage is AppStage.SignedIn && stage.workspace != null && link == AppLink.Account) {
            controller.navigate(HomeRoute(HomeTab.Account.name)) {
                popUpTo<HomeRoute> { inclusive = true }
                launchSingleTop = true
            }
            viewModel.onEvent(AppEvent.LinkHandled(link))
        }
    }
    ScopedNavHost(controller, scope = scope, startDestination = destination) {
        composable<BootstrapRoute> {
            AppPage {
                AppBrand()
                when (val current = stage) {
                    is AppStage.Failed -> {
                        AppFeedback(failureText(current.failure))
                        AppButton(
                            appString(AppString.TryAgain),
                            { viewModel.onEvent(AppEvent.BootstrapRequested) },
                        )
                    }
                    else -> AppLoading()
                }
            }
        }
        onboardingRoutes({ viewModel.onEvent(AppEvent.BootstrapRequested) })
        authRoutes()
        if (stage is AppStage.SignedIn) {
            if (stage.workspace == null) {
                verificationRoutes(stage.sessionId) {
                    controller.navigate(WorkspaceRoute) {
                        popUpTo<VerificationRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
                workspaceRoutes(stage.sessionId) {
                    controller.navigate(VerificationRoute) {
                        popUpTo<WorkspaceRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
            } else {
                homeRoutes(stage.workspace) {
                    controller.navigate(SettingsRoute) { launchSingleTop = true }
                }
                settingsRoutes(securitySettings = { AppLockSettings() }) {
                    controller.popBackStack()
                }
            }
        }
    }
}
