@file:OptIn(
    androidx.compose.ui.test.ExperimentalTestApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.LocalBackGestureDispatcher
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.fajar.starter.app.navigation.ScopedNavHost
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.fixtures.TestBackDispatcher
import kotlin.test.*
import kotlinx.coroutines.job
import kotlinx.serialization.Serializable
import org.junit.Test

class NavigationOwnershipTest {
    @Test
    fun backCannotLeaveTheAppWhileTheRootPageIsReturningFromAnInnerPage() = runComposeUiTest {
        val compose = this
        val owner = TestNavigationOwner()
        val backDispatcher = TestBackDispatcher()
        lateinit var controller: NavHostController
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner,
                    LocalBackGestureDispatcher provides backDispatcher,
                ) {
                    controller = rememberNavController()
                    AppTheme {
                        Box(Modifier.size(360.dp, 600.dp)) {
                            ScopedNavHost(controller, "account", NavigationProbeRoute) {
                                composable<NavigationProbeRoute> { AppText("Root") }
                                composable<NavigationDetailProbeRoute> { AppText("Detail") }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Root").assertExists()
            compose.runOnIdle { controller.navigate(NavigationDetailProbeRoute) }
            compose.onNodeWithText("Detail").assertExists()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { controller.popBackStack() }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle {
                assertNotEquals(
                    Lifecycle.State.RESUMED,
                    controller.currentBackStackEntry?.lifecycle?.currentState,
                )
                backDispatcher.pressBack()
            }
            compose.runOnIdle { assertEquals(0, backDispatcher.unhandled) }
            compose.mainClock.advanceTimeBy(300)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Root").assertExists()
            compose.runOnIdle {
                assertEquals(
                    Lifecycle.State.RESUMED,
                    controller.currentBackStackEntry?.lifecycle?.currentState,
                )
                backDispatcher.pressBack()
                assertEquals(1, backDispatcher.unhandled)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { owner.close() }
        }
    }

    @Test
    fun replacingTheAccountGraphClearsOldViewModelsAndCancelsTheirWork() = runComposeUiTest {
        val compose = this
        val owner = TestNavigationOwner()
        val scope = mutableStateOf("first")
        val visible = mutableStateOf(true)
        val probes = mutableListOf<NavigationProbe>()
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner,
                ) {
                    val controller = rememberNavController()
                    AppTheme {
                        Box(Modifier.size(360.dp, 600.dp)) {
                            if (visible.value)
                                ScopedNavHost(controller, scope.value, NavigationProbeRoute) {
                                    composable<NavigationProbeRoute> {
                                        val probe = viewModel {
                                            NavigationProbe().also(probes::add)
                                        }
                                        AppText("Page ${probe.value}")
                                    }
                                }
                        }
                    }
                }
            }
            compose.onNodeWithText("Page 0").assertExists()
            val first = probes.single()
            compose.runOnIdle { visible.value = false }
            compose.onNodeWithText("Page 0").assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithText("Page 0").assertExists()
            compose.runOnIdle { assertSame(first, probes.single()) }
            compose.runOnIdle { scope.value = "second" }
            compose.onNodeWithText("Page 0").assertExists()
            compose.runOnIdle {
                assertEquals(2, probes.size)
                assertEquals(1, first.cleared)
                assertFalse(first.work.isActive)
                assertTrue(probes.last().work.isActive)
                scope.value = "signed-out"
            }
            compose.onNodeWithText("Page 0").assertExists()
            compose.runOnIdle {
                assertEquals(3, probes.size)
                assertEquals(listOf(1, 1, 0), probes.map { it.cleared })
            }
        } finally {
            compose.runOnIdle { owner.close() }
        }
    }

    @Test
    fun restoredCompositionPreservesTheExistingRouteViewModelAndState() = runComposeUiTest {
        val compose = this
        val owner = TestNavigationOwner()
        val probes = mutableListOf<NavigationProbe>()
        val visible = mutableStateOf(true)
        var registry = SaveableStateRegistry(null) { true }
        try {
            compose.setContent {
                if (visible.value)
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides owner,
                        LocalLifecycleOwner provides owner,
                        LocalSaveableStateRegistry provides registry,
                    ) {
                        val controller = rememberNavController()
                        AppTheme {
                            Box(Modifier.size(360.dp, 600.dp)) {
                                ScopedNavHost(controller, "same-account", NavigationProbeRoute) {
                                    composable<NavigationProbeRoute> {
                                        val probe = viewModel {
                                            NavigationProbe().also(probes::add)
                                        }
                                        AppText("Page ${probe.value}")
                                    }
                                }
                            }
                        }
                    }
            }
            compose.onNodeWithText("Page 0").assertExists()
            val original = probes.single()
            compose.runOnIdle { original.value = 42 }
            compose.onNodeWithText("Page 42").assertExists()
            compose.runOnIdle {
                val saved = registry.performSave()
                visible.value = false
                registry = SaveableStateRegistry(saved) { true }
            }
            compose.onNodeWithText("Page 42").assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithText("Page 42").assertExists()
            compose.runOnIdle {
                assertSame(original, probes.single())
                assertEquals(0, original.cleared)
                assertTrue(original.work.isActive)
            }
        } finally {
            compose.runOnIdle { owner.close() }
        }
    }
}

@Serializable data object NavigationDetailProbeRoute

@Serializable data object NavigationProbeRoute

private class TestNavigationOwner : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    fun close() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

private class NavigationProbe : ViewModel() {
    var value by mutableIntStateOf(0)
    val work = viewModelScope.coroutineContext.job
    var cleared = 0

    override fun onCleared() {
        cleared++
    }
}
