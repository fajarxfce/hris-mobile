package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.usecases.MaintainCollaborationRuntimeOwnership
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The application process owns observation independently of activities and chat routes. */
class ApplicationCollaborationLifetime(
    private val maintain: MaintainCollaborationRuntimeOwnership,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val started = MutableStateFlow(false)
    private val mutableState =
        MutableStateFlow<AppResult<CollaborationOwner?>>(AppResult.Success(null))
    val state = mutableState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                maintain().collect { mutableState.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value =
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unexpected,
                            "Chat connection stopped.",
                            code = "client.collaboration_runtime_stopped",
                        )
                    )
            }
        }
    }

    override fun close() {
        lifetime.cancel()
    }

    suspend fun closeAndJoin() {
        close()
        lifetime.join()
    }
}
