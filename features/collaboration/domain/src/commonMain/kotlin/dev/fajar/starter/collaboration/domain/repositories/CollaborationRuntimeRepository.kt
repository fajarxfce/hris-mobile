package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.StateFlow

/** Store I/O and SDK ownership only. Use cases admit installation and live delivery separately. */
interface CollaborationRuntimeRepository {
    /** Success owns a paused client with its scoped identity/credentials durably installed. */
    suspend fun install(
        owner: CollaborationOwner,
        command: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease>

    suspend fun restore(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?>

    /** Close obsolete resources, never their stored facts. Null closes every owned runtime. */
    suspend fun closeOtherOwners(retainedOwner: CollaborationOwner?): AppResult<Unit>
}

/** The application keeps this resource through its owned SDK work and closes it on replacement. */
interface CollaborationRuntimeLease {
    val owner: CollaborationOwner
    val deviceId: String
    val installedOperationId: String
    val credentialFailures: StateFlow<Failure?>

    suspend fun retryCredentialWrite(): AppResult<Unit>

    /** Bounded wait; the repository retains unfinished cleanup and its exclusive store lock. */
    suspend fun close(): AppResult<Unit>
}
