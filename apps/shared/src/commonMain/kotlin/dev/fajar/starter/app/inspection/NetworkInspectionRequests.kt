package dev.fajar.starter.app.inspection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** App navigation handoff: keep the latest tap until the active graph can consume it. */
class NetworkInspectionRequests : AutoCloseable {
    private val mutableState =
        MutableStateFlow<NetworkInspectionLaunchState>(NetworkInspectionLaunchState.Idle)
    val state = mutableState.asStateFlow()

    fun request() {
        mutableState.update { previous ->
            if (previous == NetworkInspectionLaunchState.Closed) previous
            else NetworkInspectionLaunchState.Requested()
        }
    }

    fun handled(request: NetworkInspectionLaunchState.Requested) {
        mutableState.compareAndSet(request, NetworkInspectionLaunchState.Idle)
    }

    override fun close() {
        mutableState.value = NetworkInspectionLaunchState.Closed
    }
}

sealed interface NetworkInspectionLaunchState {
    data object Idle : NetworkInspectionLaunchState

    class Requested internal constructor() : NetworkInspectionLaunchState

    data object Closed : NetworkInspectionLaunchState
}
