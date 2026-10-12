package dev.fajar.starter.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.SavedState
import androidx.savedstate.read
import androidx.savedstate.savedState

/** Saves one navigation stack together with its admitted account/company scope. */
class ScopedNavigationState
internal constructor(
    val controller: NavHostController,
    internal val viewModels: NavigationViewModels,
    private var restored: SavedState? = null,
) {
    private var scope: String? = null

    /** Called before the first graph is attached, after bootstrap has resolved authority. */
    internal fun prepare(nextScope: String) {
        if (scope == null) {
            val snapshot = restored
            restored = null
            if (
                snapshot != null &&
                    snapshot.read {
                        contains("format") &&
                            getInt("format") == 1 &&
                            contains("scope") &&
                            getString("scope") == nextScope &&
                            contains("navigation")
                    }
            ) {
                controller.restoreState(snapshot.read { getSavedState("navigation") })
            } else {
                // A retained Activity can outlive an obsolete stack; clear only navigation owners.
                viewModels.viewModelStore.clear()
            }
        }
        scope = nextScope
    }

    internal fun save(): SavedState? {
        val currentScope = scope ?: return restored
        val navigation = controller.saveState() ?: return null
        return savedState {
            putInt("format", 1)
            putString("scope", currentScope)
            putSavedState("navigation", navigation)
        }
    }
}

/** This owner retains no Activity, controller, page, or callback. */
internal class NavigationViewModels : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCleared() {
        viewModelStore.clear()
    }
}

@Composable
fun rememberScopedNavigationState(): ScopedNavigationState {
    val viewModels = viewModel { NavigationViewModels() }
    lateinit var controller: NavHostController
    // The SDK saver cannot validate an as-yet-unresolved account/company graph.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        controller = rememberNavController()
    }
    return rememberSaveable(
        controller,
        saver =
            Saver<ScopedNavigationState, SavedState>(
                save = { it.save() },
                restore = { ScopedNavigationState(controller, viewModels, it) },
            ),
    ) {
        ScopedNavigationState(controller, viewModels)
    }
}
