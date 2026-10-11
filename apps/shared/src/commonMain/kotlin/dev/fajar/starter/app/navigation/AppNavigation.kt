package dev.fajar.starter.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.fajar.starter.app.bootstrap.*
import dev.fajar.starter.auth.presentation.navigation.*
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.home.presentation.catalog.HomeService
import dev.fajar.starter.home.presentation.home.HomeTab
import dev.fajar.starter.home.presentation.navigation.*
import dev.fajar.starter.inbox.presentation.navigation.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.onboarding.presentation.navigation.*
import dev.fajar.starter.people.presentation.navigation.*
import dev.fajar.starter.security.presentation.navigation.AppLockSettings
import dev.fajar.starter.settings.presentation.navigation.*
import dev.fajar.starter.workforce.presentation.attendance.AttendanceEffect
import dev.fajar.starter.workforce.presentation.navigation.*
import dev.fajar.starter.workspace.domain.entities.CompanyAvailability
import dev.fajar.starter.workspace.presentation.navigation.*
import dev.fajar.starter.workspace.presentation.policy.*
import kotlinx.coroutines.flow.Flow
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppNavigation(controller: NavHostController, incomingLinks: Flow<String>) {
    val viewModel = koinViewModel<AppViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stage = state.accessFailure?.let { AppStage.Failed(it) } ?: state.stage
    val bootstrapStage = rememberUpdatedState(stage)
    val companyPolicyState =
        rememberUpdatedState(
            CompanyPolicyState(
                (stage as? AppStage.SignedIn)?.workspace?.company?.name.orEmpty(),
                state.companyPolicy?.availability ?: CompanyAvailability.Unknown,
                state.refreshingWorkspace,
                state.changingWorkspace,
                state.workspaceRefreshFailure,
            )
        )
    val companyBlocked =
        stage is AppStage.SignedIn &&
            stage.workspace != null &&
            state.companyPolicy?.availability != CompanyAvailability.Available
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleStartEffect(viewModel) {
        viewModel.onEvent(AppEvent.ForegroundEntered)
        onStopOrDispose { viewModel.onEvent(AppEvent.ForegroundLeft) }
    }
    LaunchedEffect(incomingLinks, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            incomingLinks.collect { viewModel.onEvent(AppEvent.LinkReceived(it)) }
        }
    }
    val destination =
        when (stage) {
            AppStage.Onboarding -> OnboardingRoute
            AppStage.SignedOut -> LoginRoute
            is AppStage.SignedIn ->
                when {
                    stage.workspace == null -> VerificationRoute
                    companyBlocked -> CompanyPolicyRoute
                    else -> HomeRoute()
                }
            else -> BootstrapRoute
        }
    val scope =
        when (stage) {
            is AppStage.SignedIn ->
                "account/${stage.sessionId}/${stage.workspace?.id ?: "selection"}/${if (companyBlocked) "policy" else "ready"}"
            AppStage.Onboarding -> "onboarding"
            AppStage.SignedOut -> "signed-out"
            else -> "bootstrap"
        }
    LaunchedEffect(scope, state.pendingLink) {
        val link = state.pendingLink
        if (
            stage is AppStage.SignedIn &&
                stage.workspace != null &&
                !companyBlocked &&
                link == AppLink.Account
        ) {
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
                when (val current = bootstrapStage.value) {
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
                companyPolicyRoutes(companyPolicyState) { event ->
                    when (event) {
                        CompanyPolicyEvent.RefreshRequested ->
                            viewModel.onEvent(AppEvent.WorkspaceRefreshRequested)
                        CompanyPolicyEvent.CompanyChangeRequested ->
                            viewModel.onEvent(AppEvent.CompanyChangeRequested)
                    }
                }
                if (!companyBlocked) {
                    val openService: (HomeService) -> Unit = { service ->
                        when (service) {
                            HomeService.Attendance ->
                                controller.navigate(AttendanceRoute) { launchSingleTop = true }
                            HomeService.Calendar ->
                                controller.navigate(WorkCalendarRoute) { launchSingleTop = true }
                            HomeService.Profile ->
                                controller.navigate(ProfileRoute) { launchSingleTop = true }
                        }
                    }
                    homeRoutes(
                        stage.workspace,
                        onService = openService,
                        onAllServices = {
                            controller.navigate(ServicesRoute) { launchSingleTop = true }
                        },
                        onSessions = {
                            controller.navigate(SessionsRoute) { launchSingleTop = true }
                        },
                        attendance = {
                            AttendanceHomeContent(stage.workspace.id) { effect ->
                                when (effect) {
                                    AttendanceEffect.VerifyAccount ->
                                        controller.navigate(VerificationRoute) {
                                            launchSingleTop = true
                                        }
                                    AttendanceEffect.RecheckWorkspace ->
                                        viewModel.onEvent(AppEvent.WorkspaceRefreshRequested)
                                }
                            }
                        },
                        inbox = {
                            InboxContent(
                                stage.workspace.id,
                                onItem = {
                                    controller.navigate(InboxItemRoute(it)) {
                                        launchSingleTop = true
                                    }
                                },
                                onVerify = {
                                    controller.navigate(VerificationRoute) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        },
                        inboxBadge = { InboxBadgeContent(stage.workspace.id) },
                        employees = {
                            EmployeesContent(
                                stage.workspace.id,
                                onVerify = {
                                    controller.navigate(VerificationRoute) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        },
                    ) {
                        controller.navigate(SettingsRoute) { launchSingleTop = true }
                    }
                    servicesRoutes(stage.workspace.id, onService = openService) {
                        controller.popBackStack()
                    }
                    peopleRoutes(
                        stage.workspace.id,
                        onVerify = {
                            controller.navigate(VerificationRoute) { launchSingleTop = true }
                        },
                    ) {
                        controller.popBackStack()
                    }
                    employeesRoutes(
                        stage.workspace.id,
                        onVerify = {
                            controller.navigate(VerificationRoute) { launchSingleTop = true }
                        },
                    ) {
                        controller.popBackStack()
                    }
                    inboxRoutes(
                        stage.workspace.id,
                        onItem = {
                            controller.navigate(InboxItemRoute(it)) { launchSingleTop = true }
                        },
                        onVerify = {
                            controller.navigate(VerificationRoute) { launchSingleTop = true }
                        },
                        onBack = { controller.popBackStack() },
                    )
                    verificationRoutes(stage.sessionId) { controller.popBackStack() }
                    workforceRoutes(
                        stage.workspace.id,
                        onVerify = {
                            controller.navigate(VerificationRoute) { launchSingleTop = true }
                        },
                        onRecheckWorkspace = {
                            viewModel.onEvent(AppEvent.WorkspaceRefreshRequested)
                        },
                        onBack = { controller.popBackStack() },
                    )
                    sessionRoutes(stage.sessionId) { controller.popBackStack() }
                    settingsRoutes(securitySettings = { AppLockSettings() }) {
                        controller.popBackStack()
                    }
                }
            }
        }
    }
}
