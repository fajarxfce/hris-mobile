package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** One selected-owner observation. An unvalidated replacement cannot start another bootstrap. */
class ObserveCollaborationCredentialRecovery(private val runtimes: CollaborationRuntimeRepository) {
    operator fun invoke(owner: CollaborationOwner): Flow<Unit> = flow {
        var validated: Pair<String, String>? = null
        var rejected: Pair<String, String>? = null
        var awaitingValidation = false
        runtimes.credentialSignals().collect { signal ->
            if (signal.owner != owner || signal.deviceId.isBlank() || signal.operationId.isBlank())
                return@collect
            val generation = signal.deviceId to signal.operationId
            when (signal.state) {
                CollaborationCredentialState.Validated -> {
                    // A response from the rejected generation cannot validate its replacement.
                    if (
                        generation != rejected &&
                            (rejected == null || generation.first == rejected?.first)
                    ) {
                        validated = generation
                        awaitingValidation = false
                    }
                }
                CollaborationCredentialState.Rejected -> {
                    if (
                        !awaitingValidation &&
                            generation != rejected &&
                            (validated == null || validated == generation)
                    ) {
                        rejected = generation
                        awaitingValidation = true
                        emit(Unit)
                    }
                }
            }
        }
    }
}
