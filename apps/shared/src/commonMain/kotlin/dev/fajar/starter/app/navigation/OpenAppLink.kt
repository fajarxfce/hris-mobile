package dev.fajar.starter.app.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import dev.fajar.starter.home.presentation.home.HomeTab
import dev.fajar.starter.home.presentation.navigation.*
import dev.fajar.starter.inbox.presentation.navigation.InboxItemRoute
import dev.fajar.starter.inbox.presentation.navigation.InboxRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Wait for the admitted graph's destination to finish entering before replacing its stack. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun NavHostController.awaitAppLinkNavigation(scope: String) {
    currentBackStackEntryFlow
        .flatMapLatest { entry -> entry.lifecycle.currentStateFlow.map { entry to it } }
        .first { (entry, state) ->
            state == Lifecycle.State.RESUMED &&
                entry.destination.hierarchy.any { it.route == "application/$scope" }
        }
}

internal fun NavHostController.openAppLink(link: AppLink) {
    when (link) {
        AppLink.Overview -> openHomeTab(HomeTab.Home)
        AppLink.Account -> openHomeTab(HomeTab.Account)
        AppLink.Inbox -> openHomeTab(HomeTab.Inbox)
        AppLink.DataSync ->
            navigate(DataSyncRoute) {
                popUpTo<HomeRoute>()
                launchSingleTop = true
            }
        AppLink.PendingActions ->
            navigate(PendingActionsRoute) {
                popUpTo<HomeRoute>()
                launchSingleTop = true
            }
        is AppLink.InboxItem -> {
            navigate(InboxRoute) {
                popUpTo<HomeRoute>()
                launchSingleTop = true
            }
            navigate(InboxItemRoute(link.inboxId)) { launchSingleTop = true }
        }
    }
}

private fun NavHostController.openHomeTab(tab: HomeTab) {
    navigate(HomeRoute(tab.name)) {
        popUpTo<HomeRoute> { inclusive = true }
        launchSingleTop = true
    }
}
