package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.common.result.*

/** A failed retry is not fresh authorization to replace a previously confirmed denial. */
internal fun retainCollaborationCommandFailure(previous: Failure?, received: Failure): Failure =
    if (previous?.kind in collaborationDenialKinds && received.kind !in collaborationDenialKinds)
        requireNotNull(previous)
    else received

private val collaborationDenialKinds = setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
