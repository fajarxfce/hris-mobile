package dev.fajar.starter.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.fajar.starter.app.bootstrap.AppEvent
import dev.fajar.starter.app.bootstrap.AppStage
import dev.fajar.starter.app.bootstrap.AppViewModel
import dev.fajar.starter.auth.presentation.navigation.LoginRoute
import dev.fajar.starter.auth.presentation.navigation.authRoutes
import dev.fajar.starter.dashboard.presentation.navigation.DashboardRoute
import dev.fajar.starter.dashboard.presentation.navigation.dashboardRoutes
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.onboarding.presentation.navigation.OnboardingRoute
import dev.fajar.starter.onboarding.presentation.navigation.onboardingRoutes
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppNavigation() {
    val viewModel = koinViewModel<AppViewModel>()
    val stage by viewModel.state.collectAsStateWithLifecycle()
    val controller = rememberNavController()
    LaunchedEffect(stage) {
        val destination =
            when (stage) {
                AppStage.Onboarding -> OnboardingRoute
                AppStage.SignedOut -> LoginRoute
                AppStage.SignedIn -> DashboardRoute
                else -> BootstrapRoute
            }
        if (controller.currentDestination?.hasRoute(destination::class) == true)
            return@LaunchedEffect
        controller.navigate(destination) {
            popUpTo(controller.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    NavHost(controller, startDestination = BootstrapRoute) {
        composable<BootstrapRoute> {
            AppPage {
                AppBrand()
                when (val current = stage) {
                    is AppStage.Failed -> {
                        AppFeedback(current.message)
                        AppButton("Try again", { viewModel.onEvent(AppEvent.BootstrapRequested) })
                    }
                    else -> AppLoading()
                }
            }
        }
        onboardingRoutes({ viewModel.onEvent(AppEvent.BootstrapRequested) })
        authRoutes()
        dashboardRoutes()
    }
}
