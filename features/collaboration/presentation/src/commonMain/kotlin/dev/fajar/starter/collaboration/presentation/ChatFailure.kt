package dev.fajar.starter.collaboration.presentation

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind

internal fun chatRequiresReconnect(failure: Failure?): Boolean =
    failure?.kind == FailureKind.Unauthorized &&
        failure.code == "client.collaboration_sdk_unauthorized"
