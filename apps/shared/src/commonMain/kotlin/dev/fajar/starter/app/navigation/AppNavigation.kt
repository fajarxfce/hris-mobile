package dev.fajar.starter.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import dev.fajar.starter.designsystem.theme.LocalAppMotion
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
            is AppStage.SignedIn -> DashboardRoute()
            else -> BootstrapRoute
        }
    // Authentication boundaries replace the whole graph immediately. A transition must not
    // retain the previous account's screen or saved tab state after access is lost.
    key((stage as? AppStage.SignedIn)?.sessionId, destination) {
        val controller = rememberNavController()
        val motion = LocalAppMotion.current
        LaunchedEffect(state.pendingLink) {
            val link = state.pendingLink
            if (stage is AppStage.SignedIn && link != null) {
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
        NavHost(
            controller,
            startDestination = destination,
            enterTransition = { fadeIn(tween(motion.pageMillis)) },
            exitTransition = { fadeOut(tween(motion.pageMillis)) },
            popEnterTransition = { fadeIn(tween(motion.pageMillis)) },
            popExitTransition = { fadeOut(tween(motion.pageMillis)) },
        ) {
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
                onNotifications = {
                    controller.navigate(NotificationRoute) { launchSingleTop = true }
                },
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
}
