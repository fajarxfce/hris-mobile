package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.common.result.*

/** Matrix codes only; provider descriptions/details never become diagnostics or UI. */
internal fun collaborationSdkFailure(code: String, retryAfterMillis: ULong?): Failure {
    val kind =
        when (code) {
            "M_UNKNOWN_TOKEN",
            "M_MISSING_TOKEN",
            "M_UNAUTHORIZED" -> FailureKind.Unauthorized
            "M_FORBIDDEN",
            "M_GUEST_ACCESS_FORBIDDEN",
            "M_USER_DEACTIVATED",
            "M_USER_LOCKED",
            "M_USER_SUSPENDED" -> FailureKind.AccessDenied
            "M_NOT_FOUND" -> FailureKind.Unavailable
            // Matrix uses M_UNKNOWN for generic server failures, including gateway503/busy.
            // Keep the original operation retryable; this code does not prove a user restriction.
            "M_UNKNOWN",
            "M_LIMIT_EXCEEDED",
            "M_RESOURCE_LIMIT_EXCEEDED" -> FailureKind.Service
            "M_CONNECTION_TIMEOUT" -> FailureKind.Timeout
            "M_CONNECTION_FAILED" -> FailureKind.Network
            else -> FailureKind.Unexpected
        }
    return Failure(
        kind,
        "Chat request could not be completed.",
        code =
            if (code == "M_NOT_FOUND") "client.collaboration_sdk_not_found"
            else
                when (kind) {
                    FailureKind.Unauthorized -> "client.collaboration_sdk_unauthorized"
                    FailureKind.AccessDenied -> "client.collaboration_sdk_access_denied"
                    else -> "client.collaboration_sdk_unavailable"
                },
        retryAfterSeconds =
            retryAfterMillis
                ?.takeIf { code == "M_LIMIT_EXCEEDED" }
                ?.coerceAtMost(86_400_000uL)
                ?.let { ((it + 999uL) / 1000uL).toLong() },
    )
}

/** A simultaneous local shutdown/storage fault cannot erase a received SDK denial. */
internal fun collaborationSdkAcquisitionFailure(received: AppResult<*>, current: Failure): Failure {
    val denied = (received as? AppResult.Failed)?.failure
    val kinds = setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
    return if (denied?.kind in kinds && current.kind !in kinds) requireNotNull(denied) else current
}
