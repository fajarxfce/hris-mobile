package dev.fajar.starter.collaboration.presentation.setup

import dev.fajar.starter.collaboration.domain.entities.CollaborationDeviceSetup
import dev.fajar.starter.common.result.Failure

enum class ChatSetupPhase {
    CHECKING,
    REGISTER,
    WAITING,
    RETRY_PROVISIONING,
    BOOTSTRAP,
    RESUME,
    READY,
    REVOKED,
    UNAVAILABLE,
}

enum class ChatSetupOperation {
    REGISTER,
    RETRY_PROVISIONING,
    BOOTSTRAP,
}

data class ChatSetupState(
    val phase: ChatSetupPhase = ChatSetupPhase.CHECKING,
    val device: CollaborationDeviceSetup? = null,
    val retainedOperation: ChatSetupOperation? = null,
    val readyDeviceId: String? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    val localFailure: Failure? = null,
    val failure: Failure? = null,
)
