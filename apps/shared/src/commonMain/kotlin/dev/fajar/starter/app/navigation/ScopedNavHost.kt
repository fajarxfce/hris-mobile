package dev.fajar.starter.app.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.createGraph
import dev.fajar.starter.designsystem.theme.LocalAppMotion

/**
 * The host retains one controller across scope changes and configuration recreation. A distinct
 * root graph lets Navigation clear old entry ViewModelStores and saved back stacks itself.
 */
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
    key(scope) {
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
