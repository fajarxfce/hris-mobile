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
    private val signals:
        (CollaborationSpaceLocalAccess) -> Flow<AppResult<CollaborationSpaceInboxRepairSignals>>,
    private val historySignals: (CollaborationSpaceLocalAccess) -> Flow<AppResult<String?>>,
    private val catalog:
        (CollaborationSpaceLocalAccess) -> Flow<
                AppResult<CollaborationSpaceConversationCatalogVersion?>
            >,
    private val destination:
        (CollaborationSpaceLocalAccess) -> Flow<AppResult<CollaborationSpaceInboxVersion?>>,
    private val historyTurn:
        suspend (
            CollaborationSpaceLocalAccess, CollaborationSpaceInboxHistoryFrame, String, Boolean,
        ) -> AppResult<CollaborationSpaceInboxHistoryTurnWorker.Outcome>,
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
    /** One latest snapshot per input; slow metadata never prevents command service. */
    operator fun invoke(access: CollaborationSpaceLocalAccess): Flow<AppResult<Unit>> =
        flow {
                coroutineScope {
                    val inputs = MutableStateFlow(Inputs())
                    val observations =
                        listOf(
                            launch {
                                messages(access).collect { value ->
                                    inputs.update { it.copy(messages = value) }
                                }
                            },
                            launch {
                                signals(access).collect { value ->
                                    inputs.update { it.copy(signals = value) }
                                }
                            },
                            launch {
                                historySignals(access).collect { value ->
                                    inputs.update { it.copy(history = value) }
                                }
                            },
                            launch {
                                catalog(access).collect { value ->
                                    inputs.update { it.copy(catalog = value) }
                                }
                            },
                            launch {
                                destination(access).collect { value ->
                                    inputs.update { it.copy(destination = value) }
                                }
                            },
                        )
                    val commands = CommandSession(access)
                    val history = HistorySession(access)
                    var consumed: Inputs? = null
                    var lastCommand: Input? = null
                    var commandOutcome: AppResult<Unit>? = null
                    try {
                        while (true) {
                            val input =
                                if (history.continuing) inputs.value
                                else inputs.first { it != consumed }
                            val command = input.command()
                            if (command != null && command != lastCommand) {
                                lastCommand = command
                                commands.process(command)?.let { result ->
                                    currentCoroutineContext().ensureActive()
                                    commandOutcome = result
                                    emit(
                                        if (result is AppResult.Failed) result
                                        else
                                            history.outcome?.takeIf { it is AppResult.Failed }
                                                ?: result
                                    )
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            // A command arriving during a write takes its turn before another
                            // history step.
                            val latest = inputs.value
                            if (latest.command() != lastCommand) {
                                consumed = input
                                continue
                            }
                            consumed = latest
                            history.process(latest.frame())
                            currentCoroutineContext().ensureActive()
                            val outcome =
                                when {
                                    commandOutcome == null -> null
                                    commandOutcome is AppResult.Failed -> commandOutcome
                                    history.outcome is AppResult.Failed -> history.outcome
                                    else -> commandOutcome
                                }
                            if (outcome != null) emit(outcome)
                            yield()
                        }
                    } finally {
                        observations.forEach { it.cancel() }
                        // coroutineScope joins every child before this collected lifetime finishes.
                    }
                }
            }
            .distinctUntilChanged()

    /** Ephemeral seed/request state belongs to this one command inventory subscription. */
    private inner class CommandSession(val access: CollaborationSpaceLocalAccess) {
        var previous: List<CollaborationSpaceMessageRecord>? = null
        var receivedRequest = false
        var previousRequest: String? = null
        var seedRecoveryPending = false
        var seedFailure: Failure? = null

        suspend fun process(input: Input): AppResult<Unit>? {
            val records = input.messages
            val request = input.signals
            if (records is AppResult.Failed || request is AppResult.Failed) {
                return if (records is AppResult.Failed) records else request as AppResult.Failed
            }
            val current = (records as AppResult.Success).value
            val selected = collaborationSpaceInboxRepairDemand(access, previous, current)
            if (selected is AppResult.Failed) {
                return selected
            }
            val demand = (selected as AppResult.Success).value
            val seedSelection =
                collaborationSpaceInboxSeedDemand(access, previous, current, seedRecoveryPending)
            if (seedSelection is AppResult.Failed) {
                return seedSelection
            }
            val originals = (seedSelection as AppResult.Success).value
            val signal = (request as AppResult.Success).value.latestDemand
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
                return null
            if (originals.isNotEmpty()) seedRecoveryPending = true
            val outcome = execute(demand, originals, requested, initializing) ?: unfinished()
            if (seedRecoveryPending && outcome is AppResult.Failed)
                seedFailure = seedFailure ?: outcome.failure
            return if (outcome is AppResult.Failed) outcome
            else seedFailure?.let { AppResult.Failed(it) } ?: outcome
        }

        private suspend fun execute(
            demand: CollaborationSpaceInboxRepairDemand,
            originals: List<CollaborationSpaceMessageIntent>,
            requested: Boolean,
            initializing: Boolean,
        ): AppResult<Unit>? =
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
                    val read = readReference(access, target.conversationId, target.messageId)
                    if (read is AppResult.Failed) return@withTimeoutOrNull read
                    val reference = (read as AppResult.Success).value ?: continue
                    val repaired = reconcile(access, reference)
                    if (repaired is AppResult.Failed) return@withTimeoutOrNull repaired
                    yield()
                }
                if (requested || demand.requiresScan) repairPasses(access)
                else AppResult.Success(Unit)
            }
    }

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

    /** Domain scheduling consumes recovery before entering a suspended finite history turn. */
    private inner class HistorySession(val access: CollaborationSpaceLocalAccess) {
        var schedule = CollaborationSpaceInboxHistorySchedule()
        val continuing
            get() = schedule.continuing

        var outcome: AppResult<Unit>? = null

        suspend fun process(frame: AppResult<CollaborationSpaceInboxHistoryFrame>?) {
            if (frame == null) return
            if (frame is AppResult.Failed) {
                schedule = suspendCollaborationSpaceInboxHistorySchedule(schedule)
                outcome = frame
                return
            }
            val selected =
                scheduleCollaborationSpaceInboxHistory(schedule, (frame as AppResult.Success).value)
            if (selected is AppResult.Failed) {
                schedule =
                    completeCollaborationSpaceInboxHistorySchedule(
                        schedule,
                        CollaborationSpaceInboxHistoryTurnStatus.Failed,
                    )
                outcome = selected
                return
            }
            val decision = (selected as AppResult.Success).value
            if (decision is CollaborationSpaceInboxHistoryScheduleDecision.Wait) return
            decision as CollaborationSpaceInboxHistoryScheduleDecision.Run
            schedule = decision.consumed
            val result =
                withTimeoutOrNull(5.seconds) {
                    historyTurn(
                        access,
                        decision.frame,
                        Uuid.random().toString(),
                        decision.recoverParked,
                    )
                } ?: unfinished()
            currentCoroutineContext().ensureActive()
            val status =
                when (result) {
                    is AppResult.Failed ->
                        collaborationSpaceInboxHistoryFailureStatus(result.failure)
                    is AppResult.Success ->
                        when (result.value) {
                            CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle ->
                                CollaborationSpaceInboxHistoryTurnStatus.Idle
                            is CollaborationSpaceInboxHistoryTurnWorker.Outcome.Advanced ->
                                CollaborationSpaceInboxHistoryTurnStatus.Advanced
                        }
                }
            schedule = completeCollaborationSpaceInboxHistorySchedule(schedule, status)
            outcome =
                when {
                    status == CollaborationSpaceInboxHistoryTurnStatus.Contended -> null
                    result is AppResult.Failed -> result
                    else -> AppResult.Success(Unit)
                }
        }
    }

    /** Null fields are unobserved; Success(null) is an observed missing durable value. */
    private data class Inputs(
        val messages: AppResult<List<CollaborationSpaceMessageRecord>>? = null,
        val signals: AppResult<CollaborationSpaceInboxRepairSignals>? = null,
        val history: AppResult<String?>? = null,
        val catalog: AppResult<CollaborationSpaceConversationCatalogVersion?>? = null,
        val destination: AppResult<CollaborationSpaceInboxVersion?>? = null,
    ) {
        fun command(): Input? =
            if (messages != null && signals != null) Input(messages, signals) else null

        fun frame(): AppResult<CollaborationSpaceInboxHistoryFrame>? {
            if (
                messages is AppResult.Failed &&
                    collaborationSpaceDirectoryResponseClosesRead(messages.failure)
            )
                return messages
            val failure =
                listOf(signals, history, catalog, destination)
                    .filterIsInstance<AppResult.Failed>()
                    .firstOrNull()
            if (failure != null) return failure
            if (signals == null || history == null || catalog == null || destination == null)
                return null
            val version = (destination as AppResult.Success).value
            return AppResult.Success(
                CollaborationSpaceInboxHistoryFrame(
                    CollaborationSpaceInboxHistoryTrigger(
                        (history as AppResult.Success).value,
                        (catalog as AppResult.Success).value,
                        (signals as AppResult.Success).value.completedDemand,
                        version?.generation,
                    ),
                    version,
                )
            )
        }
    }

    private data class Input(
        val messages: AppResult<List<CollaborationSpaceMessageRecord>>,
        val signals: AppResult<CollaborationSpaceInboxRepairSignals>,
    )
}
