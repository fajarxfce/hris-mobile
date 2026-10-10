package dev.fajar.starter.auth.presentation.verification

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind

/**
 * Presentation outcome: an ambiguous credential rotation must offer fresh sign-in, not resubmit.
 */
fun verificationNeedsNewSignIn(failure: Failure): Boolean =
    failure.code !in setOf("mfa_rate_limited", "authentication_rate_limited") &&
        (failure.kind in
            setOf(
                FailureKind.Network,
                FailureKind.Timeout,
                FailureKind.Service,
                FailureKind.Storage,
                FailureKind.Unexpected,
            ) || (failure.kind == FailureKind.Unauthorized && failure.code != "invalid_mfa_code"))
