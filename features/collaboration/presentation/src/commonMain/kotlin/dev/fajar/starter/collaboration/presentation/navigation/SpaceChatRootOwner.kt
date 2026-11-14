package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceDirectoryResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.ObserveLocalCollaborationSpace
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/**
 * A window's root owns these stores independently of its Company navigation stack. Admission comes
 * from the domain observer; this owner never opens a route or acquires a transport.
 */
@KoinViewModel
class SpaceChatRootOwner(observe: ObserveLocalCollaborationSpace) :
    MviViewModel<SpaceChatRootState, Nothing, Nothing>(SpaceChatRootState()) {
    internal var store: SpaceChatRootStore? = null
        private set

    private var lifetime = 0L

    init {
        viewModelScope.launch {
            observe().collect { result ->
                ensureActive()
                when (result) {
                    is AppResult.Success -> {
                        val access = result.value
                        if (store?.access != access) {
                            store?.close()
                            store = access?.let(::SpaceChatRootStore)
                            lifetime++
                        }
                        updateState {
                            SpaceChatRootState(
                                access = access,
                                loading = false,
                                lifetime = lifetime,
                            )
                        }
                    }
                    is AppResult.Failed -> {
                        if (collaborationSpaceDirectoryResponseClosesRead(result.failure)) {
                            if (store != null) lifetime++
                            store?.close()
                            store = null
                        }
                        val retained = store?.access
                        updateState {
                            SpaceChatRootState(
                                access = retained,
                                loading = false,
                                failure = result.failure,
                                lifetime = lifetime,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        store?.close()
        store = null
        super.onCleared()
    }
}

data class SpaceChatRootState(
    val access: CollaborationSpaceLocalAccess? = null,
    val loading: Boolean = true,
    val failure: Failure? = null,
    val lifetime: Long = 0,
) {
    override fun toString() = "SpaceChatRootState([redacted])"
}

/** One admitted account/Group owns hub models and the existing bounded thread graph. */
internal class SpaceChatRootStore(val access: CollaborationSpaceLocalAccess) :
    ViewModelStoreOwner, AutoCloseable {
    private val models = ViewModelStore()
    var closed = false
        private set

    override val viewModelStore: ViewModelStore
        get() {
            check(!closed) { "The Group navigation store is closed" }
            return models
        }

    override fun close() {
        if (closed) return
        closed = true
        models.clear()
    }
}
