package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.StateFlow

/** Store I/O and SDK ownership only. Use cases admit installation and live delivery separately. */
interface CollaborationRuntimeRepository {
    /** Whether this host has a protected native implementation for first device activation. */
    val supportsDeviceActivation: Boolean
        get() = false

    /**
     * Atomically retain an already published runtime or exclude other runtime acquisitions while
     * the caller inspects the original private store. Never opens, drains or creates an SDK.
     */
    suspend fun reserveInspection(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeInspection> =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Chat storage inspection is unavailable.",
                code = "client.collaboration_inspection_unavailable",
            )
        )

    /** Owned protected metadata only; never opens an SDK client or acquires server authority. */
    suspend fun handoverIntent(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeHandoverIntent?>

    /**
     * Gates and drains the exact old store, then persists an immutable local target. The caller
     * must verify provider key publication beforehand and current server admission afterward. A
     * failed/cancelled stage cannot authorize server freeze; retry reads the same durable intent.
     */
    suspend fun stageHandover(
        owner: CollaborationOwner,
        deviceId: String,
        intent: CollaborationRuntimeHandoverIntent,
    ): AppResult<CollaborationRuntimeHandoverIntent>

    /**
     * Excludes/drains the original namespace, verifies its private identity under an existing-only
     * lease, and retains the exact local target. It never restores a client or grants server
     * authority. The owning use case must establish provider publication and current admission.
     */
    suspend fun stageExistingHandover(
        currentParent: CollaborationNativeParent,
        target: CollaborationExistingRuntimeHandover,
    ): AppResult<CollaborationRuntimeHandoverIntent> =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Original chat storage is unavailable.",
                code = "client.collaboration_existing_handover_unavailable",
            )
        )

    /** Retain current parent and foreground resources; invalidate obsolete pending acquisitions. */
    suspend fun updateRetention(retention: CollaborationRuntimeRetention): AppResult<Unit>

    /**
     * Borrow or restore a scoped resource for at most the supplied remaining lifetime
     * (1..60,000ms). Never enrolls. A new resource remains unpublished until its owning use case
     * accepts it.
     */
    suspend fun acquireBackground(
        owner: CollaborationOwner,
        deviceId: String,
        lifetimeMillis: Long,
    ): AppResult<CollaborationBackgroundRuntimeLease?>

    /** Finite receipt lookup in an existing owned, known room. Never opens or enrolls. */
    suspend fun readOwnReadReceipts(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationOwnReadReceipts>

    /** Finite unthreaded metadata write; the caller owns live admission and durable retry. */
    suspend fun sendReadReceipt(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        eventId: String,
        visibility: CollaborationReadReceiptVisibility,
    ): AppResult<Unit>

    /** Uses an installed SDK bearer and the fixed managed endpoint. Never opens or enrolls. */
    suspend fun activatePusher(owner: CollaborationOwner, deviceId: String): AppResult<Unit>

    /**
     * Ensures the installed, owned SDK has completed a sync without opening a room or resuming
     * message delivery. Success acknowledges the sync response, not device-key publication; the
     * server still verifies recipient readiness. The caller owns current admission and the
     * background/foreground lifetime.
     */
    suspend fun synchronizeDevice(owner: CollaborationOwner, deviceId: String): AppResult<Unit>

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
        untilPosition: String? = null,
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

sealed interface CollaborationRuntimeInspection {
    data object Open : CollaborationRuntimeInspection

    /**
     * The caller releases this exclusion after every inspection outcome, including cancellation.
     */
    interface Reserved : CollaborationRuntimeInspection {
        val owner: CollaborationOwner
        val deviceId: String

        fun isCurrent(): Boolean

        fun close()
    }
}

/** Finite reference to an application runtime. Closing it never closes a retained foreground. */
interface CollaborationBackgroundRuntimeLease {
    val owner: CollaborationOwner
    val deviceId: String
    val credentialFailures: StateFlow<Failure?>
    val interruptions: StateFlow<Failure?>

    /** Synchronous acceptance after final admission, with no network or suspension. */
    fun publish(): AppResult<Unit>

    /** Releases only this reference; unfinished native drain stays application-owned. */
    suspend fun close(): AppResult<Unit>
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
