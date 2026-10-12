@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
import androidx.navigation.compose.composable
import dev.fajar.starter.app.navigation.ScopedNavHost
import dev.fajar.starter.app.navigation.ScopedNavigationState
import dev.fajar.starter.app.navigation.rememberScopedNavigationState
import dev.fajar.starter.designsystem.components.AppButton
import dev.fajar.starter.designsystem.components.AppText
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.*
import kotlinx.coroutines.job
import kotlinx.serialization.Serializable
import org.junit.Test

class NavigationRestorationTest {
    @Test
    fun aFreshProcessDefersTheSavedDetailUntilItsCompanyIsAdmitted() =
        restore(target = FIRST, replaceOwner = true)

    @Test
    fun anotherCompanyDiscardsTheSavedStackAndClearsRetainedNavigationViewModels() =
        restore(target = "account/first/company/second", replaceOwner = false)

    @Test
    fun revokedSessionRestoresOnlyTheSignedOutGraph() =
        restore(target = "signed-out", replaceOwner = true)

    @Test
    fun processRecreationDuringBootstrapKeepsTheUnresolvedSavedStack() =
        restore(target = FIRST, replaceOwner = true, repeatBootstrap = true)

    private fun restore(target: String, replaceOwner: Boolean, repeatBootstrap: Boolean = false) =
        runComposeUiTest {
            val compose = this
            val owner = mutableStateOf(RestorationOwner())
            val visible = mutableStateOf(true)
            val scope = mutableStateOf<String?>(FIRST)
            var registry = SaveableStateRegistry(null) { true }
            val probes = mutableListOf<RestorationProbe>()
            lateinit var navigation: ScopedNavigationState
            try {
                compose.setContent {
                    if (visible.value) {
                        CompositionLocalProvider(
                            LocalViewModelStoreOwner provides owner.value,
                            LocalLifecycleOwner provides owner.value,
                            LocalSaveableStateRegistry provides registry,
                        ) {
                            navigation = rememberScopedNavigationState()
                            AppTheme {
                                Box(Modifier.size(360.dp, 600.dp)) {
                                    val admitted = scope.value
                                    if (admitted == null) AppText("Restoring")
                                    else
                                        ScopedNavHost(navigation, admitted, RestorationRoot) {
                                            composable<RestorationRoot> {
                                                AppText("Root $admitted")
                                            }
                                            if (admitted == FIRST) {
                                                composable<RestorationDetail> {
                                                    viewModel {
                                                        RestorationProbe().also(probes::add)
                                                    }
                                                    var value by rememberSaveable {
                                                        mutableIntStateOf(0)
                                                    }
                                                    Column {
                                                        AppText("Detail $value")
                                                        AppButton("Change", { value++ })
                                                    }
                                                }
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
                compose.onNodeWithText("Root $FIRST").assertExists()
                compose.runOnIdle { navigation.controller.navigate(RestorationDetail) }
                compose.onNodeWithText("Detail 0").assertExists()
                compose.onNodeWithText("Change").performClick()
                compose.onNodeWithText("Detail 1").assertExists()
                val original = probes.single()

                fun recreate(closeOwner: Boolean) {
                    compose.runOnIdle {
                        val saved = registry.performSave()
                        visible.value = false
                        scope.value = null
                        if (closeOwner) {
                            owner.value.close()
                            owner.value = RestorationOwner()
                        }
                        registry = SaveableStateRegistry(saved) { true }
                    }
                    compose.onNodeWithText("Detail 1").assertDoesNotExist()
                    compose.runOnIdle { visible.value = true }
                    compose.onNodeWithText("Restoring").assertExists()
                }

                recreate(replaceOwner)
                if (repeatBootstrap) recreate(true)
                compose.runOnIdle { scope.value = target }
                if (target == FIRST) {
                    compose.onNodeWithText("Detail 1").assertExists()
                    compose.runOnIdle {
                        assertEquals(2, probes.size)
                        assertNotSame(original, probes.last())
                        assertTrue(probes.last().work.isActive)
                    }
                } else {
                    compose.onNodeWithText("Root $target").assertExists()
                    compose.onNodeWithText("Detail 1").assertDoesNotExist()
                    compose.runOnIdle {
                        assertEquals(1, probes.size)
                        assertNull(navigation.controller.previousBackStackEntry)
                    }
                }
                compose.runOnIdle {
                    assertEquals(1, original.cleared)
                    assertFalse(original.work.isActive)
                }
            } finally {
                compose.runOnIdle { owner.value.close() }
            }
        }

    private companion object {
        const val FIRST = "account/first/company/first"
    }
}

@Serializable internal data object RestorationRoot

@Serializable internal data object RestorationDetail

private class RestorationOwner : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    fun close() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

private class RestorationProbe : ViewModel() {
    val work = viewModelScope.coroutineContext.job
    var cleared = 0

    override fun onCleared() {
        cleared++
    }
}
