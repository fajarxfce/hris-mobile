@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.composable
import androidx.savedstate.SavedState
import androidx.savedstate.read
import androidx.savedstate.savedState
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

class IndependentNavigationStacksTest {
    @Test
    fun replacingCompanyLeavesTheOtherStackAndWindowModelAlive() = exercise(changeCompany = true)

    @Test fun compositionRecreationRetainsBothStacksWithoutReplacingTheirModels() = exercise()

    @Test
    fun aFreshWindowRestoresEachStackOnlyUnderItsMatchingAdmission() =
        exercise(freshWindow = true, changeCompany = true)

    private fun exercise(freshWindow: Boolean = false, changeCompany: Boolean = false) =
        runComposeUiTest {
            var owner = StackWindowOwner()
            var visible by mutableStateOf(true)
            var company by mutableStateOf("company/first")
            var registry = SaveableStateRegistry(null) { true }
            val navigation = mutableMapOf<String, ScopedNavigationState>()
            val probes = mutableMapOf<String, MutableList<StackProbe>>()
            val windows = mutableListOf<StackProbe>()
            try {
                setContent {
                    if (visible) {
                        CompositionLocalProvider(
                            LocalViewModelStoreOwner provides owner,
                            LocalLifecycleOwner provides owner,
                            LocalSaveableStateRegistry provides registry,
                        ) {
                            viewModel { StackProbe().also(windows::add) }
                            AppTheme {
                                Column(Modifier.size(420.dp, 700.dp)) {
                                    for ((name, scope) in
                                        listOf("group" to "group/first", "company" to company)) {
                                        key(name) {
                                            val stack =
                                                rememberScopedNavigationState(ownerKey = name)
                                            SideEffect { navigation[name] = stack }
                                            ScopedNavHost(
                                                stack,
                                                scope,
                                                IndependentStackRoot,
                                                Modifier.size(400.dp, 320.dp),
                                            ) {
                                                composable<IndependentStackRoot> {
                                                    AppText("$name root $scope")
                                                }
                                                composable<IndependentStackDetail> {
                                                    viewModel {
                                                        StackProbe().also {
                                                            probes
                                                                .getOrPut(name) { mutableListOf() }
                                                                .add(it)
                                                        }
                                                    }
                                                    var count by rememberSaveable {
                                                        mutableIntStateOf(0)
                                                    }
                                                    Column {
                                                        AppText("$name detail $count")
                                                        AppButton("Change $name", { count++ })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                onNodeWithText("group root group/first").assertExists()
                onNodeWithText("company root company/first").assertExists()
                runOnIdle {
                    assertNotSame(
                        navigation.getValue("group").viewModels,
                        navigation.getValue("company").viewModels,
                    )
                    navigation.values.forEach { it.controller.navigate(IndependentStackDetail) }
                }
                onNodeWithText("Change group").performClick()
                onNodeWithText("Change company").performClick()
                onNodeWithText("group detail 1").assertExists()
                onNodeWithText("company detail 1").assertExists()
                val originalGroup = probes.getValue("group").single()
                val originalCompany = probes.getValue("company").single()
                val originalWindow = windows.single()

                if (changeCompany && !freshWindow) {
                    runOnIdle { company = "company/second" }
                } else {
                    runOnIdle {
                        // The OS captures a value snapshot before the old window is destroyed.
                        // Compose's holder saver returns its mutable backing map; retaining that
                        // map here would let the retired entry's onCleared erase the test's save.
                        val captured =
                            registry.performSave().mapValues { (_, values) ->
                                values.map(::snapshotNavigationValue)
                            }
                        registry = SaveableStateRegistry(captured) { true }
                        visible = false
                    }
                    waitForIdle()
                    runOnIdle {
                        if (freshWindow) {
                            owner.close()
                            owner = StackWindowOwner()
                        }
                        if (changeCompany) company = "company/second"
                        visible = true
                    }
                }
                onNodeWithText("group detail 1")
                    .assertExists("Restored stacks: " + onRoot().printToString())
                if (changeCompany) {
                    onNodeWithText("company root company/second").assertExists()
                    onNodeWithText("company detail 1").assertDoesNotExist()
                } else onNodeWithText("company detail 1").assertExists()
                runOnIdle {
                    val currentGroup = probes.getValue("group").last()
                    assertTrue(currentGroup.work.isActive)
                    assertEquals(0, currentGroup.cleared)
                    if (freshWindow) {
                        assertEquals(2, probes.getValue("group").size)
                        assertNotSame(originalGroup, currentGroup)
                        assertEquals(1, originalGroup.cleared)
                        assertEquals(1, originalWindow.cleared)
                    } else {
                        assertEquals(1, probes.getValue("group").size)
                        assertSame(originalGroup, currentGroup)
                        assertEquals(0, originalWindow.cleared)
                        assertTrue(originalWindow.work.isActive)
                    }
                    assertEquals(if (changeCompany) 1 else 0, originalCompany.cleared)
                    if (!changeCompany) assertTrue(originalCompany.work.isActive)
                    assertEquals(1, probes.getValue("company").size)
                    if (changeCompany)
                        assertNull(navigation.getValue("company").controller.previousBackStackEntry)
                }
            } finally {
                runOnIdle { visible = false }
                waitForIdle()
                runOnIdle { owner.close() }
            }
            runOnIdle {
                (probes.values.flatten() + windows).forEach {
                    assertEquals(1, it.cleared)
                    assertFalse(it.work.isActive)
                }
            }
        }
}

@Serializable internal data object IndependentStackRoot

@Serializable internal data object IndependentStackDetail

private class StackWindowOwner : ViewModelStoreOwner, LifecycleOwner {
    override val viewModelStore = ViewModelStore()
    override val lifecycle =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }

    fun close() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

private class StackProbe : ViewModel() {
    val work = viewModelScope.coroutineContext.job
    var cleared = 0

    override fun onCleared() {
        cleared++
    }
}

/** Snapshot only the value types emitted by these navigation fixtures, without shared holders. */
private fun snapshotNavigationValue(value: Any?): Any? =
    when (value) {
        null,
        is String,
        is Int,
        is Long,
        is Boolean,
        is Float,
        is Double -> value
        is MutableIntState -> mutableIntStateOf(value.intValue)
        is SavedState ->
            savedState(
                value.read { toMap() }.mapValues { (_, item) -> snapshotNavigationValue(item) }
            )
        is Map<*, *> -> value.mapValues { (_, item) -> snapshotNavigationValue(item) }
        is List<*> -> value.map(::snapshotNavigationValue)
        else -> error("Unsupported navigation snapshot value: " + value::class)
    }
