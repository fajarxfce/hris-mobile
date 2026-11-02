package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.StateFlow

/** Store I/O and SDK ownership only. Use cases admit installation and live delivery separately. */
interface CollaborationRuntimeRepository {
    /** Uses an installed SDK bearer and the fixed managed endpoint. Never opens or enrolls. */
    suspend fun activatePusher(owner: CollaborationOwner, deviceId: String): AppResult<Unit>

    /** Reads only the protected scoped record. No SDK client, store lease, sync or network. */
    suspend fun selfIdentity(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationSelfIdentity?>

    /**
     * True means this owner has no open client and restore may follow fresh admission. False
     * retains a client with capacity for this focus. History-initiated closing requires an idle
     * queue writer; previously requested cleanup may finish. Never deletes stored facts, restores,
     * bootstraps or retries. Unfinished cleanup stays owned.
     */
    suspend fun prepareHistoryReopen(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<Boolean>

    /** One bounded metadata page from the owned SDK identity; never advances its sync cursor. */
    suspend fun readProviderHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        position: String?,
    ): AppResult<CollaborationProviderHistoryPage>

    /**
     * One latest-visible-event lookup, using the installed SDK identity without changing cursors.
     */
    suspend fun findHistoryHead(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationHistoryHead>

    /** Finite read from an installed runtime and known room; the caller admits network access. */
    suspend fun readOriginalHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<CollaborationOriginalWindow>

    /** Uses an installed runtime and known room; never resumes queue delivery. */
    suspend fun acquireMessageQueue(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationMessageQueueLease>

    /** Observes an existing application-owned runtime without borrowing its close capability. */
    suspend fun isOpen(owner: CollaborationOwner, deviceId: String): AppResult<Boolean>

    /** Success owns a paused client with its scoped identity/credentials durably installed. */
    suspend fun install(
        owner: CollaborationOwner,
        command: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease>

    suspend fun restore(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?>

    /** Uses an installed owned runtime, joins the admitted room and acquires a child timeline. */
    suspend fun enterTimeline(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationTimelineLease>

    /** Close obsolete resources, never their stored facts. Null closes every owned runtime. */
    suspend fun closeOtherOwners(retainedOwner: CollaborationOwner?): AppResult<Unit>
}

/** The application keeps this resource through its owned SDK work and closes it on replacement. */
interface CollaborationRuntimeLease {
    val owner: CollaborationOwner
    val deviceId: String
    val installedOperationId: String
    val credentialFailures: StateFlow<Failure?>

    /** Publish paused ownership after final admission. No suspension or network; repeatable. */
    fun publish(): AppResult<Unit>

    suspend fun retryCredentialWrite(): AppResult<Unit>

    /** Bounded wait; the repository retains unfinished cleanup and its exclusive store lock. */
    suspend fun close(): AppResult<Unit>
}
