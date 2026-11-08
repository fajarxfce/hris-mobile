package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure

enum class CollaborationActivationMode {
    INSPECT,
    CONTINUE,
    RESUME,
    AUTOMATIC,
}

enum class CollaborationActivationStep {
    REGISTER,
    WAITING,
    RETRY_PROVISIONING,
    BOOTSTRAP,
    RESUME,
    READY,
    RECOVERY_REQUIRED,
    REVOKED,
}

/** Public progress only; protected bootstrap credentials never reach an observer. */
data class CollaborationActivationProgress(
    val owner: CollaborationOwner,
    val device: CollaborationDeviceSetup?,
    val step: CollaborationActivationStep,
    val retainedAction: CollaborationSetupAction? = null,
    val recoveryFailure: Failure? = null,
)

enum class CollaborationActivationStore {
    FIRST_INITIALIZATION,
    ORIGINAL_ACCOUNT,
    RECOVERY_REQUIRED,
    TRANSFER_PENDING,
}
