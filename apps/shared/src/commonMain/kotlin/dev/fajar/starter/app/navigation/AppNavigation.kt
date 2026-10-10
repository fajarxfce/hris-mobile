package dev.fajar.starter.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.fajar.starter.app.bootstrap.AppEvent
import dev.fajar.starter.app.bootstrap.AppStage
import dev.fajar.starter.app.bootstrap.AppViewModel
import dev.fajar.starter.auth.presentation.navigation.LoginRoute
import dev.fajar.starter.auth.presentation.navigation.authRoutes
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.dashboard.presentation.home.DashboardTab
import dev.fajar.starter.dashboard.presentation.navigation.DashboardRoute
import dev.fajar.starter.dashboard.presentation.navigation.dashboardRoutes
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.files.presentation.navigation.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.notifications.presentation.navigation.NotificationRoute
import dev.fajar.starter.notifications.presentation.navigation.notificationRoutes
import dev.fajar.starter.onboarding.presentation.navigation.OnboardingRoute
import dev.fajar.starter.onboarding.presentation.navigation.onboardingRoutes
import dev.fajar.starter.security.presentation.navigation.AppLockSettings
import dev.fajar.starter.settings.presentation.navigation.*
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppNavigation(incomingLinks: Flow<String>) {
    val viewModel = koinViewModel<AppViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val environment = koinInject<AppEnvironment>()
    val stage = state.stage
    val controller = rememberNavController()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(incomingLinks, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            incomingLinks.collect { viewModel.onEvent(AppEvent.LinkReceived(it)) }
        }
    }
    LaunchedEffect(stage, state.pendingLink) {
        val destination =
            when (stage) {
                AppStage.Onboarding -> OnboardingRoute
                AppStage.SignedOut -> LoginRoute
                AppStage.SignedIn -> DashboardRoute()
                else -> BootstrapRoute
            }
        val protectedScreen =
            controller.currentDestination?.hasRoute<DashboardRoute>() == true ||
                controller.currentDestination?.hasRoute<NotificationRoute>() == true ||
                controller.currentDestination?.hasRoute<SettingsRoute>() == true ||
                controller.currentDestination?.hasRoute<FilesRoute>() == true
        if (
            !(stage == AppStage.SignedIn && protectedScreen) &&
                controller.currentDestination?.hasRoute(destination::class) != true
        ) {
            controller.navigate(destination) {
                popUpTo(controller.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
        val link = state.pendingLink
        if (stage == AppStage.SignedIn && link != null) {
            when (link) {
                AppLink.Inbox ->
                    controller.navigate(NotificationRoute) {
                        popUpTo<DashboardRoute>()
                        launchSingleTop = true
                    }
                else ->
                    controller.navigate(
                        DashboardRoute(
                            when (link) {
                                AppLink.Activity -> DashboardTab.Activity
                                AppLink.Account -> DashboardTab.Account
                                else -> DashboardTab.Overview
                            }
                        )
                    ) {
                        popUpTo<DashboardRoute> { inclusive = true }
                        launchSingleTop = true
                    }
            }
            viewModel.onEvent(AppEvent.LinkHandled(link))
        }
    }
    NavHost(controller, startDestination = BootstrapRoute) {
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
        settingsRoutes(securitySettings = { AppLockSettings() }) { controller.popBackStack() }
        filesRoutes { controller.popBackStack() }
        dashboardRoutes(
            onNotifications = { controller.navigate(NotificationRoute) { launchSingleTop = true } },
            onFiles = { controller.navigate(FilesRoute) { launchSingleTop = true } },
            onSettings = { controller.navigate(SettingsRoute) { launchSingleTop = true } },
        )
        notificationRoutes(
            onBack = { controller.popBackStack() },
            onDestination = {
                viewModel.onEvent(AppEvent.LinkReceived("${environment.linkScheme}://app/$it"))
            },
        )
    }
}
