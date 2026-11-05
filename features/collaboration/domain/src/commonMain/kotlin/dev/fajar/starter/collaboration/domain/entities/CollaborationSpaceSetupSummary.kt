package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

enum class CollaborationSpaceSetupKind {
    RETRY,
    BOOTSTRAP,
}

enum class CollaborationSpaceSetupPhase {
    PENDING,
    UNCONFIRMED,
    RETRIED,
    BOOTSTRAPPED,
}

/** Local queue projection deliberately contains no gateway credentials or SDK/private keys. */
data class CollaborationSpaceSetupSummary(
    val id: String,
    val registrationId: String,
    val nativeSessionId: String,
    val deviceId: String,
    val kind: CollaborationSpaceSetupKind,
    val phase: CollaborationSpaceSetupPhase,
    val createdAt: Instant,
    val attempts: Int,
    val failure: Failure?,
) {
    override fun toString() =
        "CollaborationSpaceSetupSummary(kind=$kind, phase=$phase, attempts=$attempts)"
}
