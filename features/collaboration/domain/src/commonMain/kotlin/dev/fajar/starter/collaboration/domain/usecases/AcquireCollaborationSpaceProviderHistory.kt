package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** One admitted provider page. Atomic storage owns progress; the caller owns durable wakes. */
class AcquireCollaborationSpaceProviderHistory(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val transport: CollaborationSpaceDirectTransportRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val provider: CollaborationSpaceProviderHistoryRepository,
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
        purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Incoming,
    ): AppResult<CollaborationSpaceProviderHistoryAcquisition> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceConversation(conversation, runtime.owner) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId)
        )
            return pageFailure("invalid_request", FailureKind.Validation)
        return Acquisition(runtime, conversation, origin, purpose).run()
    }

    /** Admission, deadlines and the expected checkpoint belong to this single invocation. */
    private inner class Acquisition(
        val runtime: CollaborationSpaceRuntimeReady,
        val conversation: CollaborationSpaceConversation,
        val origin: CollaborationHistoryPageOrigin,
        val purpose: CollaborationHistoryPurpose,
    ) {
        val owner = runtime.owner
        val access = owner.localDirectoryAccess()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var nativeDeadline: Instant? = null
        var restriction: Failure? = null
        var retained: CollaborationSpaceRoomBinding? = null
        var observed: CollaborationSpaceDirectTransport? = null
        var transportStarted: TimeMark? = null

        suspend fun run(): AppResult<CollaborationSpaceProviderHistoryAcquisition> {
            try {
                return withTimeoutOrNull(15_000) { acquire() }
                    ?: failed(pageFailure("timeout", FailureKind.Timeout).failure)
            } finally {
                if (restriction != null)
                    withContext(NonCancellable) {
                        withTimeoutOrNull(2_000) { runCatching { runtimes.closeSpace(owner) } }
                    }
            }
        }

        suspend fun bindRoom(): AppResult<Unit> {
            val admitted = current()
            if (admitted is AppResult.Failed) return admitted
            val loaded = checked { bindings.getByConversation(access, conversation.id) }
            if (loaded is AppResult.Failed) return loaded
            val binding =
                (loaded as AppResult.Success).value ?: return pageFailure("binding_missing")
            if (
                !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation != conversation.localMetadata()
            )
                return pageFailure("binding_changed")
            retained = binding
            return current()
        }

        suspend fun admitTransport(): AppResult<Unit> {
            current().let { if (it is AppResult.Failed) return it }
            val binding = requireNotNull(retained)
            transportStarted = timeSource.markNow()
            val received = transport.read(conversation)
            restrict(received, conversationControl = true)
            current().let { if (it is AppResult.Failed) return it }
            if (received is AppResult.Failed) return failed(received.failure)
            val value = (received as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return pageFailure("unsupported_platform", FailureKind.Unavailable)
            if (
                !validCollaborationSpaceDirectTransport(value) ||
                    value.conversation != conversation ||
                    value.room != binding.room ||
                    value.device.id != runtime.deviceId ||
                    value.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
                    value.device.platform != platform
            )
                return pageFailure("transport_changed")
            observed = value
            return current()
        }

        suspend fun acquire(): AppResult<CollaborationSpaceProviderHistoryAcquisition> {
            bindRoom().let { if (it is AppResult.Failed) return it }
            val binding = requireNotNull(retained)
            val traversal =
                CollaborationHistoryTraversal(
                    conversation.id,
                    binding.room.roomId,
                    runtime.deviceId,
                    purpose,
                )
            val loaded = checked { history.progress(access, traversal) }
            if (loaded is AppResult.Failed) return loaded
            val previous = (loaded as AppResult.Success).value
            if (
                previous != null &&
                    (!validCollaborationHistoryProgress(previous) ||
                        previous.provider == null ||
                        (previous.incoming != null) !=
                            (purpose == CollaborationHistoryPurpose.Incoming))
            )
                return pageFailure("invalid_progress")
            val latest = origin == CollaborationHistoryPageOrigin.Latest
            if (!latest && previous?.exhausted == true)
                return AppResult.Success(
                    CollaborationSpaceProviderHistoryAcquisition(
                        runtime,
                        traversal,
                        origin,
                        previous,
                        previous,
                        0,
                    )
                )
            admitTransport().let { if (it is AppResult.Failed) return it }
            val position = if (latest) null else previous?.next
            val until = collaborationHistoryUntilPosition(traversal, previous, origin)
            val received = provider.readPage(runtime, binding.room.roomId, position, until)
            // Capture bounded values before any later suspension can mutate a provider-owned list.
            val snapshot =
                (received as? AppResult.Success)?.value?.let {
                    if (it.physical.events.size > 20) null
                    else it.copy(physical = it.physical.copy(events = it.physical.events.toList()))
                }
            restrict(received)
            current().let { if (it is AppResult.Failed) return it }
            if (received is AppResult.Failed) return failed(received.failure)
            if (
                snapshot == null ||
                    !validCollaborationSpaceIncomingProviderPage(
                        runtime,
                        binding,
                        requireNotNull(observed),
                        snapshot,
                        traversal,
                        previous,
                        origin,
                    )
            )
                return pageFailure("invalid_page")
            return retainPage(traversal, previous, snapshot)
        }

        suspend fun retainPage(
            traversal: CollaborationHistoryTraversal,
            previous: CollaborationHistoryProgress?,
            snapshot: CollaborationSpaceProviderHistoryPage,
        ): AppResult<CollaborationSpaceProviderHistoryAcquisition> {
            val saved = checked { history.stage(access, traversal, previous, snapshot, origin) }
            if (saved is AppResult.Failed) return saved
            if (!(saved as AppResult.Success).value) return pageFailure("progress_changed")
            // Counts or an EOF boolean alone cannot prove a retained provider transition.
            val loaded = checked { history.progress(access, traversal) }
            if (loaded is AppResult.Failed) return loaded
            val progress =
                (loaded as AppResult.Success).value ?: return pageFailure("progress_changed")
            val physical = snapshot.physical
            val result =
                CollaborationSpaceProviderHistoryAcquisition(
                    runtime,
                    traversal,
                    origin,
                    previous,
                    progress,
                    physical.events.size,
                )
            if (
                !validCollaborationSpaceProviderAcquisition(result) ||
                    progress.next != physical.next ||
                    progress.incoming !=
                        collaborationIncomingHistoryPosition(
                            traversal,
                            previous,
                            physical,
                            origin,
                        ) ||
                    progress.provider !=
                        CollaborationHistoryProviderIdentity(
                            physical.owner.companyId,
                            physical.deviceId,
                        )
            )
                return pageFailure("progress_changed")
            return AppResult.Success(result)
        }

        suspend fun admission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = directory.admission(access)
            if (gate is AppResult.Success && validCollaborationSpaceDirectoryAdmission(gate.value))
                gate.value.denial?.let {
                    restriction = retainCollaborationSpaceConversationFailure(restriction, it)
                }
            val parent =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            currentCoroutineContext().ensureActive()
            restriction?.let {
                return AppResult.Failed(it)
            }
            if (parent is AppResult.Failed) return parent
            if (gate is AppResult.Failed) return gate
            if (!validCollaborationSpaceDirectoryAdmission((gate as AppResult.Success).value))
                return pageFailure("invalid_admission")
            val expiry =
                Instant.fromEpochMilliseconds(
                    (parent as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            nativeDeadline = nativeDeadline?.let { minOf(it, expiry) } ?: expiry
            val now = clock.now()
            if (
                now < requestedAt ||
                    now >= requireNotNull(nativeDeadline) ||
                    now - requestedAt >= 15.seconds ||
                    started.elapsedNow() >=
                        minOf(15.seconds, requireNotNull(nativeDeadline) - requestedAt)
            )
                return pageFailure("revalidation_required", FailureKind.Unavailable)
            observed?.let {
                val elapsed = requireNotNull(transportStarted).elapsedNow()
                if (
                    elapsed < Duration.ZERO ||
                        elapsed >=
                            requireNotNull(it.device.gateway).expiresAt - it.device.observedAt
                )
                    return pageFailure("revalidation_required", FailureKind.Unavailable)
            }
            return AppResult.Success(Unit)
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val expected = retained
            if (expected != null) {
                val binding = bindings.getByConversation(access, conversation.id)
                admission().let { if (it is AppResult.Failed) return it }
                if (binding is AppResult.Failed) return binding
                if ((binding as AppResult.Success).value != expected)
                    return pageFailure("binding_changed")
            }
            val open = runtimes.isSpaceOpen(owner, runtime.deviceId, runtime.operationId)
            restrict(open)
            admission().let { if (it is AppResult.Failed) return it }
            if (open is AppResult.Failed) return failed(open.failure)
            if (!(open as AppResult.Success).value) return pageFailure("runtime_changed")
            return AppResult.Success(Unit)
        }

        suspend fun <T> checked(operation: suspend () -> AppResult<T>): AppResult<T> {
            current().let { if (it is AppResult.Failed) return it }
            val result = operation()
            current().let { if (it is AppResult.Failed) return it }
            return if (result is AppResult.Failed) failed(result.failure) else result
        }

        suspend fun restrict(result: AppResult<*>, conversationControl: Boolean = false) {
            if (
                result is AppResult.Failed &&
                    (if (conversationControl)
                        collaborationSpaceConversationResponseClosesDirectory(result.failure)
                    else collaborationSpaceDirectoryResponseClosesRead(result.failure))
            ) {
                restriction =
                    retainCollaborationSpaceConversationFailure(restriction, result.failure)
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) {
                        runCatching { directory.restrict(access, result.failure) }
                    }
                }
            }
        }

        fun failed(value: Failure) = AppResult.Failed(restriction ?: value)
    }
}

private fun pageFailure(reason: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_provider_history_$reason", kind)
