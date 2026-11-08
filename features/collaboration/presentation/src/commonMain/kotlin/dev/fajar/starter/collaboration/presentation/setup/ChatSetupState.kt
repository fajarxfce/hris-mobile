package dev.fajar.starter.collaboration.presentation.setup

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

enum class ChatSetupPhase {
    CHECKING,
    REGISTER,
    WAITING,
    RETRY_PROVISIONING,
    BOOTSTRAP,
    RECONNECT,
    RESUME,
    READY,
    REVOKED,
    RECOVERY_REQUIRED,
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

internal fun CollaborationSetupAction.setupOperation(): ChatSetupOperation =
    when (this) {
        is CollaborationSetupAction.Register -> ChatSetupOperation.REGISTER
        is CollaborationSetupAction.Retry -> ChatSetupOperation.RETRY_PROVISIONING
        is CollaborationSetupAction.Bootstrap -> ChatSetupOperation.BOOTSTRAP
    }

internal fun ChatSetupState.withActivation(
    progress: CollaborationActivationProgress,
    mode: CollaborationActivationMode,
): ChatSetupState {
    val phase =
        when (progress.step) {
            CollaborationActivationStep.REGISTER -> ChatSetupPhase.REGISTER
            CollaborationActivationStep.WAITING -> ChatSetupPhase.WAITING
            CollaborationActivationStep.RETRY_PROVISIONING -> ChatSetupPhase.RETRY_PROVISIONING
            CollaborationActivationStep.BOOTSTRAP -> ChatSetupPhase.BOOTSTRAP
            CollaborationActivationStep.RESUME -> ChatSetupPhase.RESUME
            CollaborationActivationStep.READY -> ChatSetupPhase.READY
            CollaborationActivationStep.RECOVERY_REQUIRED -> ChatSetupPhase.RECOVERY_REQUIRED
            CollaborationActivationStep.REVOKED -> ChatSetupPhase.REVOKED
        }
    return copy(
        device = progress.device,
        phase = phase,
        retainedOperation = progress.retainedAction?.setupOperation(),
        readyDeviceId = progress.device?.id.takeIf { phase == ChatSetupPhase.READY },
        localFailure = null,
        failure = null,
        loading = false,
        working =
            mode != CollaborationActivationMode.INSPECT &&
                phase in
                    setOf(
                        ChatSetupPhase.REGISTER,
                        ChatSetupPhase.RETRY_PROVISIONING,
                        ChatSetupPhase.BOOTSTRAP,
                        ChatSetupPhase.RESUME,
                    ),
    )
}
