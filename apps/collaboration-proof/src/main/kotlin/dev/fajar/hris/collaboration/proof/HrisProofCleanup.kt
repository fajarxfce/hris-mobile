package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.common.result.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Fixture wait only: product close calls still return their bounded pending result. */
internal suspend fun drainHrisProofRuntime(close: suspend () -> AppResult<Unit>) {
    repeat(6) {
        when (val result = close()) {
            is AppResult.Success -> return
            is AppResult.Failed ->
                if (result.failure.code != "client.collaboration_close_pending")
                    throw HrisProofFailure(result.failure)
        }
    }
    error("Owned runtime did not finish closing")
}

/** Every resource is attempted; cleanup cannot replace the operation that actually failed. */
internal suspend fun closeHrisProofResources(
    primary: Exception?,
    operations: List<suspend () -> Unit>,
) =
    withContext(NonCancellable) {
        var first = primary
        for (operation in operations) try {
            operation()
        } catch (failure: Exception) {
            if (first == null) first = failure
            else if (first !== failure) first.addSuppressed(failure)
        }
        if (primary == null) first?.let { throw it }
    }
