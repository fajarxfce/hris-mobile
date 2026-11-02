package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimePublication
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure

/** Only Observed contains a consumed publication; Pending and Stopped retain no parent. */
sealed interface ApplicationCollaborationState {
    data object Pending : ApplicationCollaborationState

    data class Observed(val publication: AppResult<CollaborationRuntimePublication>) :
        ApplicationCollaborationState

    data class Stopped(val failure: Failure? = null) : ApplicationCollaborationState
}
