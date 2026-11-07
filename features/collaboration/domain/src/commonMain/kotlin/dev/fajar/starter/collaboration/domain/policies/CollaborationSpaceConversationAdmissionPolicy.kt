package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.common.result.*

/** Native conversation control responses only; never apply this exception to SDK/gateway I/O. */
fun collaborationSpaceConversationResponseClosesDirectory(failure: Failure): Boolean =
    collaborationSpaceDirectoryResponseClosesRead(failure) &&
        !(failure.kind == FailureKind.AccessDenied &&
            failure.code == "collaboration_peer_unavailable")
