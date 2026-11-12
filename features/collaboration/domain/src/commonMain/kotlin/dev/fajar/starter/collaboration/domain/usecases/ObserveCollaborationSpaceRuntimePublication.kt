package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeRepository
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*

/**
 * Observes exact installed identity. Retention/delivery independently admit every live operation.
 */
class ObserveCollaborationSpaceRuntimePublication(
    private val runtimes: CollaborationSpaceRuntimeRepository
) {
    operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady
    ): Flow<AppResult<CollaborationSpaceRuntimePublication?>> = flow {
        if (
            !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId)
        ) {
            emit(changed())
            return@flow
        }
        emitAll(
            runtimes
                .observePublication(runtime)
                .map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val value = result.value
                            if (
                                value != null &&
                                    (value.runtime != runtime ||
                                        !validCollaborationMessageId(value.instanceId))
                            )
                                changed()
                            else result
                        }
                    }
                }
                .distinctUntilChanged()
        )
    }

    private fun changed() =
        AppResult.Failed(
            Failure(
                FailureKind.Validation,
                "Chat runtime identity changed.",
                code = "client.collaboration_runtime_publication_changed",
            )
        )
}
