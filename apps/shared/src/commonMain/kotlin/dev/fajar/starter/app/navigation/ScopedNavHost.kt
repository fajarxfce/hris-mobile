package dev.fajar.starter.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.createGraph
import dev.fajar.starter.designsystem.theme.LocalAppMotion

/**
 * The host retains one controller across scope changes and configuration recreation. A distinct
 * root graph lets Navigation clear old entry ViewModelStores and saved back stacks itself.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScopedNavHost(
    navigation: ScopedNavigationState,
    scope: String,
    startDestination: Any,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    val controller = navigation.controller
    val graph =
        remember(navigation, scope, startDestination, builder) {
            navigation.prepare(scope)
            controller.createGraph(startDestination = startDestination, builder = builder).apply {
                route = "application/$scope"
            }
        }
    val motion = LocalAppMotion.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val entry by controller.currentBackStackEntryAsState()
    val lifecycle = entry?.lifecycle?.currentStateAsState()?.value
    val canLeave =
        entry?.destination?.id == graph.findStartDestination().id &&
            lifecycle == Lifecycle.State.RESUMED
    key(scope) {
        // Destination and NavHost callbacks registered below take precedence. This fallback
        // consumes rapid Back presses until the returning root has finished its transition.
        BackHandler(enabled = !canLeave) {}
        CompositionLocalProvider(LocalViewModelStoreOwner provides navigation.viewModels) {
            NavHost(
                controller,
                graph = graph,
                modifier = modifier.clipToBounds(),
                enterTransition = {
                    if (motion.pageMillis == 0) EnterTransition.None
                    else
                        slideInHorizontally(
                            tween(motion.pageMillis, easing = FastOutSlowInEasing)
                        ) {
                            it * direction
                        }
                },
                exitTransition = {
                    if (motion.pageMillis == 0) ExitTransition.None
                    else
                        slideOutHorizontally(
                            tween(motion.pageMillis, easing = FastOutSlowInEasing)
                        ) {
                            -it * direction / 3
                        }
                },
                popEnterTransition = {
                    if (motion.pageMillis == 0) EnterTransition.None
                    else
                        slideInHorizontally(
                            tween(motion.pageMillis, easing = FastOutSlowInEasing)
                        ) {
                            -it * direction / 3
                        }
                },
                popExitTransition = {
                    if (motion.pageMillis == 0) ExitTransition.None
                    else
                        slideOutHorizontally(
                            tween(motion.pageMillis, easing = FastOutSlowInEasing)
                        ) {
                            it * direction
                        }
                },
            )
        }
    }
}
