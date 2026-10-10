package dev.fajar.starter.network

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind

/** Rejected streaming response whose body must not be retained or read by a failure mapper. */
class HttpStatusException(val statusCode: Int) : Exception("HTTP operation rejected ($statusCode).")

fun mapHttpStatusFailure(status: Int): Failure =
    when (status) {
        400,
        422 -> Failure(FailureKind.Validation, "Check the information and try again.")
        401 ->
            Failure(
                FailureKind.Unauthorized,
                "Authentication was not accepted. Check your details.",
            )
        403 -> Failure(FailureKind.AccessDenied, "Your account cannot perform this action.")
        429 -> Failure(FailureKind.Service, "Too many requests. Try again shortly.")
        in 500..599 -> Failure(FailureKind.Service, "The service is unavailable. Try again.")
        else -> Failure(FailureKind.Unexpected, "The request could not be completed.")
    }
