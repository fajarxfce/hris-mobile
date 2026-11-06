@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import dev.fajar.starter.collaboration.presentation.navigation.ChatThreadViewModelStores
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.*
import kotlinx.serialization.Serializable
import org.junit.Test

class ChatThreadRetainedNavigationTest {
    @Test
    fun poppedRoomIsPresentDuringReentryTransitionAndAnotherGraphClearsIt() = runComposeUiTest {
        val compose = this
        val owner = RetainedNavigationOwner()
        val scope = mutableStateOf("first-company")
        val probes = mutableListOf<RetainedRoomProbe>()
        lateinit var controller: NavHostController
        try {
            setContent {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalViewModelStoreOwner provides owner,
                ) {
                    controller = rememberNavController()
                    val graphRoute = "room-cache/${scope.value}"
                    val graph =
                        remember(graphRoute) {
                            controller
                                .createGraph(startDestination = RetainedRoomRoot) {
                                    composable<RetainedRoomRoot> { AppText("Root $graphRoute") }
                                    composable<RetainedRoomRoute> { entry ->
                                        val parent =
                                            remember(entry) {
                                                controller.getBackStackEntry(graphRoute)
                                            }
                                        val stores =
                                            viewModel(viewModelStoreOwner = parent) {
                                                ChatThreadViewModelStores()
                                            }
                                        val id = entry.toRoute<RetainedRoomRoute>().id
                                        val room =
                                            viewModel(viewModelStoreOwner = entry) {
                                                stores.open(id)
                                            }
                                        val probe =
                                            viewModel(viewModelStoreOwner = room) {
                                                RetainedRoomProbe().also(probes::add)
                                            }
                                        AppText(probe.text)
                                    }
                                }
                                .apply { route = graphRoute }
                        }
                    AppTheme {
                        Box(Modifier.size(360.dp, 600.dp)) {
                            key(graphRoute) {
                                NavHost(
                                    controller,
                                    graph,
                                    enterTransition = { fadeIn(tween(180)) },
                                    exitTransition = { fadeOut(tween(180)) },
                                    popEnterTransition = { fadeIn(tween(180)) },
                                    popExitTransition = { fadeOut(tween(180)) },
                                )
                            }
                        }
                    }
                }
            }
            onNodeWithText("Root room-cache/first-company").assertExists()
            runOnIdle { controller.navigate(RetainedRoomRoute("room")) }
            onNodeWithText("Loading").assertExists()
            val first = probes.single()
            runOnIdle { first.text = "Saved conversation" }
            onNodeWithText("Saved conversation").assertExists()
            runOnIdle { controller.popBackStack() }
            onNodeWithText("Root room-cache/first-company").assertExists()
            runOnIdle { assertEquals(0, first.cleared) }

            mainClock.autoAdvance = false
            runOnIdle { controller.navigate(RetainedRoomRoute("room")) }
            mainClock.advanceTimeBy(32)
            onNodeWithText("Saved conversation").assertIsDisplayed()
            runOnIdle {
                assertEquals(1, probes.size)
                assertNotEquals(
                    Lifecycle.State.RESUMED,
                    controller.currentBackStackEntry?.lifecycle?.currentState,
                )
            }
            mainClock.advanceTimeBy(300)
            mainClock.autoAdvance = true
            runOnIdle { scope.value = "second-company" }
            onNodeWithText("Root room-cache/second-company").assertExists()
            runOnIdle {
                assertEquals(1, first.cleared)
                controller.navigate(RetainedRoomRoute("room"))
            }
            onNodeWithText("Loading").assertExists()
            onNodeWithText("Saved conversation").assertDoesNotExist()
            runOnIdle { assertEquals(2, probes.size) }
        } finally {
            mainClock.autoAdvance = true
            compose.runOnUiThread { owner.close() }
        }
    }
}

@Serializable internal data object RetainedRoomRoot

@Serializable internal data class RetainedRoomRoute(val id: String)

private class RetainedRoomProbe : ViewModel() {
    var text by mutableStateOf("Loading")
    var cleared = 0

    override fun onCleared() {
        cleared++
    }
}

private class RetainedNavigationOwner : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    fun close() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
