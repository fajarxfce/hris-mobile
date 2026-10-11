package dev.fajar.starter.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
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
    controller: NavHostController,
    scope: String,
    startDestination: Any,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    val graph =
        remember(controller, scope, startDestination, builder) {
            controller.createGraph(startDestination = startDestination, builder = builder).apply {
                route = "application/$scope"
            }
        }
    val motion = LocalAppMotion.current
    val entry by controller.currentBackStackEntryAsState()
    val lifecycle = entry?.lifecycle?.currentStateAsState()?.value
    val canLeave =
        entry?.destination?.id == graph.findStartDestination().id &&
            lifecycle == Lifecycle.State.RESUMED
    key(scope) {
        // Destination and NavHost callbacks registered below take precedence. This fallback
        // consumes rapid Back presses until the returning root has finished its transition.
        BackHandler(enabled = !canLeave) {}
        NavHost(
            controller,
            graph = graph,
            modifier = modifier,
            enterTransition = { fadeIn(tween(motion.pageMillis)) },
            exitTransition = { fadeOut(tween(motion.pageMillis)) },
            popEnterTransition = { fadeIn(tween(motion.pageMillis)) },
            popExitTransition = { fadeOut(tween(motion.pageMillis)) },
        )
    }
}
