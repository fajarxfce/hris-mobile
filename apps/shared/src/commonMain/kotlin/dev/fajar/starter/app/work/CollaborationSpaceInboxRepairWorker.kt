@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceInboxRepairProgressChange.EnsureInitialized
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceInboxRepairProgressChange.Request
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * One explicitly collected account/Group lifetime. Its caller cancels and joins before replacing
 * ownership. Native transport, foreground timers and per-conversation subscriptions are absent.
 */
class CollaborationSpaceInboxRepairWorker(
    private val messages:
        (CollaborationSpaceLocalAccess) -> Flow<AppResult<List<CollaborationSpaceMessageRecord>>>,
    private val requests: (CollaborationSpaceLocalAccess) -> Flow<AppResult<String?>>,
    private val readProgress:
        suspend (CollaborationSpaceLocalAccess) -> AppResult<
                CollaborationSpaceInboxRepairProgress?
            >,
    private val changeProgress:
        suspend (
            CollaborationSpaceLocalAccess, CollaborationSpaceInboxRepairProgressChange,
        ) -> AppResult<CollaborationSpaceInboxRepairProgress>,
    private val readPage:
        suspend (CollaborationSpaceLocalAccess, String?, Int) -> AppResult<
                CollaborationSpaceInboxRepairPage
            >,
    private val readReference:
        suspend (CollaborationSpaceLocalAccess, String, String) -> AppResult<
                CollaborationSpaceInboxRepairReference?
            >,
    private val seed:
        suspend (CollaborationSpaceLocalAccess, CollaborationSpaceMessageIntent) -> AppResult<
                Boolean
            >,
    private val reconcile:
        suspend (
            CollaborationSpaceLocalAccess, CollaborationSpaceInboxRepairReference,
        ) -> AppResult<Boolean>,
) {
    /** Failed turns park their durable progress until new demand or an explicit owner restart. */
    operator fun invoke(access: CollaborationSpaceLocalAccess): Flow<AppResult<Unit>> =
        flow {
                var previous: List<CollaborationSpaceMessageRecord>? = null
                var receivedRequest = false
                var previousRequest: String? = null
                var seedRecoveryPending = false
                var seedFailure: Failure? = null
                combine(messages(access), requests(access), ::Input).conflate().collect { input ->
                    val records = input.messages
                    val request = input.request
                    if (records is AppResult.Failed || request is AppResult.Failed) {
                        emit(
                            if (records is AppResult.Failed) records
                            else request as AppResult.Failed
                        )
                        return@collect
                    }
                    val current = (records as AppResult.Success).value
                    val selected = collaborationSpaceInboxRepairDemand(access, previous, current)
                    if (selected is AppResult.Failed) {
                        emit(selected)
                        return@collect
                    }
                    val demand = (selected as AppResult.Success).value
                    val seedSelection =
                        collaborationSpaceInboxSeedDemand(
                            access,
                            previous,
                            current,
                            seedRecoveryPending,
                        )
                    if (seedSelection is AppResult.Failed) {
                        emit(seedSelection)
                        return@collect
                    }
                    val originals = (seedSelection as AppResult.Success).value
                    val signal = (request as AppResult.Success).value
                    val initializing = !receivedRequest
                    val requested = initializing || previousRequest != signal
                    receivedRequest = true
                    previousRequest = signal
                    previous = requireNotNull(collaborationSpaceMessageInventory(access, current))
                    if (
                        !requested &&
                            !demand.requiresScan &&
                            demand.targets.isEmpty() &&
                            originals.isEmpty()
                    )
                        return@collect
                    if (originals.isNotEmpty()) seedRecoveryPending = true
                    val result =
                        withTimeoutOrNull(30.seconds) {
                            if (demand.requiresScan || initializing) {
                                val retained =
                                    changeProgress(
                                        access,
                                        if (demand.requiresScan) Request(Uuid.random().toString())
                                        else EnsureInitialized(Uuid.random().toString()),
                                    )
                                if (retained is AppResult.Failed) return@withTimeoutOrNull retained
                                previousRequest = (retained as AppResult.Success).value.latestDemand
                            }
                            for (original in originals) {
                                currentCoroutineContext().ensureActive()
                                val seeded = seed(access, original)
                                if (seeded is AppResult.Failed) {
                                    seedFailure = seeded.failure
                                    return@withTimeoutOrNull seeded
                                }
                                yield()
                            }
                            if (originals.isNotEmpty()) {
                                seedRecoveryPending = false
                                seedFailure = null
                            }
                            for (target in demand.targets) {
                                currentCoroutineContext().ensureActive()
                                val read =
                                    readReference(access, target.conversationId, target.messageId)
                                if (read is AppResult.Failed) return@withTimeoutOrNull read
                                val reference = (read as AppResult.Success).value ?: continue
                                val repaired = reconcile(access, reference)
                                if (repaired is AppResult.Failed) return@withTimeoutOrNull repaired
                                yield()
                            }
                            if (requested || demand.requiresScan) repairPasses(access)
                            else AppResult.Success(Unit)
                        }
                    val outcome = result ?: unfinished()
                    if (seedRecoveryPending && outcome is AppResult.Failed)
                        seedFailure = seedFailure ?: outcome.failure
                    emit(
                        if (outcome is AppResult.Failed) outcome
                        else seedFailure?.let { AppResult.Failed(it) } ?: outcome
                    )
                }
            }
            .distinctUntilChanged()

    /**
     * A captured pass and at most one coalesced later request; every page advances after its rows.
     */
    private suspend fun repairPasses(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        repeat(2) {
            val read = readProgress(access)
            if (read is AppResult.Failed) return read
            val retained = (read as AppResult.Success).value ?: return AppResult.Success(Unit)
            if (retained.pass == null && retained.latestDemand == retained.completedDemand)
                return AppResult.Success(Unit)
            val started = changeProgress(access, CollaborationSpaceInboxRepairProgressChange.Begin)
            if (started is AppResult.Failed) return started
            val pass = (started as AppResult.Success).value.pass ?: return AppResult.Success(Unit)
            val finished = repairPass(access, pass)
            if (finished is AppResult.Failed) return finished
            yield()
        }
        return AppResult.Success(Unit)
    }

    private suspend fun repairPass(
        access: CollaborationSpaceLocalAccess,
        initial: CollaborationSpaceInboxRepairPass,
    ): AppResult<Unit> {
        var pass = initial
        var restarts = 0
        repeat(256) {
            currentCoroutineContext().ensureActive()
            val read = readPage(access, pass.after, 16)
            if (read is AppResult.Failed) return read
            val change =
                when (val page = (read as AppResult.Success).value) {
                    CollaborationSpaceInboxRepairPage.Restart -> {
                        if (pass.after == null || restarts++ == 2) return unfinished()
                        CollaborationSpaceInboxRepairProgressChange.Restart(pass)
                    }
                    is CollaborationSpaceInboxRepairPage.Items -> {
                        for (reference in page.references) {
                            val repaired = reconcile(access, reference)
                            if (repaired is AppResult.Failed) return repaired
                            yield()
                        }
                        CollaborationSpaceInboxRepairProgressChange.Advance(pass, page.next)
                    }
                }
            val saved = changeProgress(access, change)
            if (saved is AppResult.Failed) return saved
            val current = (saved as AppResult.Success).value
            if (current.completedDemand == initial.demand) return AppResult.Success(Unit)
            val remaining = current.pass ?: return unfinished()
            if (remaining.demand != initial.demand) return unfinished()
            pass = remaining
            yield()
        }
        return unfinished()
    }

    private fun unfinished() =
        collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Unavailable)

    private data class Input(
        val messages: AppResult<List<CollaborationSpaceMessageRecord>>,
        val request: AppResult<String?>,
    )
}
