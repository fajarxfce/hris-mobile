package dev.fajar.starter.collaboration.domain.entities

/** Current metadata after one bounded transfer attempt; never permission to open an SDK. */
sealed interface CollaborationSpaceRuntimeTransferProgress {
    val transfer: CollaborationSpaceRuntimeTransfer

    /** The original SDK was staged and freeze acknowledged; only the server worker may finish. */
    data class AwaitingProvisioning(
        override val transfer: CollaborationSpaceRuntimeTransfer,
        val drain: CollaborationSpaceRuntimeDrain,
    ) : CollaborationSpaceRuntimeTransferProgress {
        override fun toString() = "AwaitingProvisioning([redacted])"
    }

    /** An exact retained target is active; fresh bootstrap and original-store adoption remain. */
    data class Provisioned(
        override val transfer: CollaborationSpaceRuntimeTransfer,
        val deviceVersion: Long,
    ) : CollaborationSpaceRuntimeTransferProgress {
        override fun toString() = "Provisioned([redacted])"
    }
}
