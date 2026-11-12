package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.captureCollaborationSpaceHistoryRecovery
import dev.fajar.starter.collaboration.domain.policies.isCollaborationSpaceIncomingQueueContention
import dev.fajar.starter.collaboration.domain.policies.matchesCollaborationSpaceHistoryRecoveryRoom
import dev.fajar.starter.collaboration.domain.policies.sameCollaborationSpaceHistoryRecoveryTurn
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceHistoryRecovery
import dev.fajar.starter.common.result.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** One bounded visit under the existing incoming worker's mutex and native parent lifetime. */
class CollaborationSpaceHistoryRecoveryService(
    private val select:
        suspend (CollaborationSpaceRuntimeReady) -> AppResult<
                CollaborationSpaceHistoryRecoverySelection
            >,
    private val original:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceIncomingSource,
            String,
            CollaborationIncomingHistoryLocator,
        ) -> AppResult<CollaborationSpaceOriginalKeyHistoryResolution>,
    private val published:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceIncomingSource,
            CollaborationAccountIncomingHistoryLocator.Space,
        ) -> AppResult<CollaborationSpaceKeyHistoryResolution>,
    private val readOriginal:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceOriginalKeyHistory, String,
        ) -> AppResult<CollaborationHistoryRecoveryRead>,
    private val readPublished:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceKeyHistory, String) -> AppResult<
                CollaborationHistoryRecoveryRead
            >,
    private val acquireOriginal:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceOriginalKeyHistory,
            CollaborationHistoryReferences,
        ) -> AppResult<CollaborationSpaceOriginalKeyPageBatch>,
    private val acquirePublished:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceKeyHistory,
            CollaborationHistoryReferences,
        ) -> AppResult<CollaborationSpaceKeyPageBatch>,
    private val completeOriginal:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceOriginalKeyHistory,
            CollaborationHistoryReferences,
            CollaborationSpaceOriginalKeyPageBatch,
        ) -> AppResult<CollaborationHistoryRecoveryPageCompletion>,
    private val completePublished:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceKeyHistory,
            CollaborationHistoryReferences,
            CollaborationSpaceKeyPageBatch,
        ) -> AppResult<CollaborationHistoryRecoveryPageCompletion>,
    private val complete:
        suspend (
            CollaborationSpaceHistoryRecoveryTurn, CollaborationSpaceHistoryRecoveryAttempt?,
        ) -> AppResult<Boolean>,
    private val defer:
        suspend (CollaborationSpaceHistoryRecoveryTurn, Failure) -> AppResult<Boolean>,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady
    ): AppResult<CollaborationSpaceHistoryRecoveryOutcome> {
        currentCoroutineContext().ensureActive()
        var captured: CollaborationSpaceHistoryRecoveryTurn? = null
        val result =
            withTimeoutOrNull(30.seconds) {
                val selected = select(runtime)
                if (selected is AppResult.Failed) return@withTimeoutOrNull selected
                val turn =
                    when (val selection = (selected as AppResult.Success).value) {
                        CollaborationSpaceHistoryRecoverySelection.Idle ->
                            return@withTimeoutOrNull AppResult.Success(
                                CollaborationSpaceHistoryRecoveryOutcome.Idle
                            )
                        is CollaborationSpaceHistoryRecoverySelection.Deferred ->
                            return@withTimeoutOrNull AppResult.Success(
                                CollaborationSpaceHistoryRecoveryOutcome.Deferred
                            )
                        is CollaborationSpaceHistoryRecoverySelection.Ready -> selection.turn
                    }
                if (turn.runtime != runtime || turn.page.locators.size > 1)
                    return@withTimeoutOrNull pending("invalid_selection", FailureKind.Validation)
                val recovery =
                    captureCollaborationSpaceHistoryRecovery(turn.recovery)
                        ?: return@withTimeoutOrNull pending(
                            "invalid_selection",
                            FailureKind.Validation,
                        )
                if (!validCollaborationSpaceHistoryRecovery(recovery))
                    return@withTimeoutOrNull pending("invalid_selection", FailureKind.Validation)
                captured =
                    turn.copy(
                        recovery = recovery,
                        page = turn.page.copy(locators = turn.page.locators.toList()),
                    )
                val stable = requireNotNull(captured)
                val attempted =
                    when (val row = stable.page.locators.singleOrNull()) {
                        null -> AppResult.Success(null)
                        is CollaborationAccountIncomingHistoryLocator.Company ->
                            originalVisit(stable, row)
                        is CollaborationAccountIncomingHistoryLocator.Space ->
                            publishedVisit(stable, row)
                    }
                if (attempted is AppResult.Failed) return@withTimeoutOrNull attempted
                when (val completed = complete(stable, (attempted as AppResult.Success).value)) {
                    is AppResult.Failed -> completed
                    is AppResult.Success ->
                        AppResult.Success(
                            if (completed.value) CollaborationSpaceHistoryRecoveryOutcome.Advanced
                            else CollaborationSpaceHistoryRecoveryOutcome.Obsolete
                        )
                }
            } ?: pending("history_timeout", FailureKind.Timeout)
        if (result is AppResult.Success) return result
        val failure = (result as AppResult.Failed).failure
        val turn = captured ?: return result
        if (
            failure.kind == FailureKind.Conflict ||
                isCollaborationSpaceIncomingQueueContention(failure)
        )
            return settleContention(turn, result)
        if (
            failure.kind in
                setOf(FailureKind.Unauthorized, FailureKind.AccessDenied, FailureKind.Validation)
        )
            return result
        val deferred =
            withTimeoutOrNull(5.seconds) { defer(turn, failure) }
                ?: return pending("history_defer_timeout", FailureKind.Timeout)
        return when (deferred) {
            is AppResult.Failed -> deferred
            is AppResult.Success ->
                AppResult.Success(
                    if (deferred.value) CollaborationSpaceHistoryRecoveryOutcome.Deferred
                    else CollaborationSpaceHistoryRecoveryOutcome.Obsolete
                )
        }
    }

    private suspend fun originalVisit(
        turn: CollaborationSpaceHistoryRecoveryTurn,
        row: CollaborationAccountIncomingHistoryLocator.Company,
    ): AppResult<CollaborationSpaceHistoryRecoveryAttempt> {
        val loaded = original(turn.runtime, turn.source, row.companyId, row.history)
        if (loaded is AppResult.Failed) return loaded
        val history =
            when (val found = (loaded as AppResult.Success).value) {
                is CollaborationSpaceOriginalKeyHistoryResolution.Ready -> found.history
                CollaborationSpaceOriginalKeyHistoryResolution.SourceUnavailable,
                CollaborationSpaceOriginalKeyHistoryResolution.MetadataUnavailable ->
                    return AppResult.Success(CollaborationSpaceHistoryRecoveryAttempt.Unavailable)
            }
        if (!matchesCollaborationSpaceHistoryRecoveryRoom(turn.recovery, history.traversal.roomId))
            return AppResult.Success(
                CollaborationSpaceHistoryRecoveryAttempt.OriginalOutsideTarget(history)
            )
        val loadedRead = readOriginal(turn.runtime, history, turn.recovery.generation)
        if (loadedRead is AppResult.Failed) return loadedRead
        val read = (loadedRead as AppResult.Success).value
        if (read !is CollaborationHistoryRecoveryRead.Pending)
            return AppResult.Success(
                CollaborationSpaceHistoryRecoveryAttempt.Original(history, read)
            )
        if (read.references.events.size > 16) return pending("invalid_page", FailureKind.Validation)
        val stable =
            read.copy(references = read.references.copy(events = read.references.events.toList()))
        val acquired = acquireOriginal(turn.runtime, history, stable.references)
        if (acquired is AppResult.Failed) return acquired
        val completed =
            completeOriginal(
                turn.runtime,
                history,
                stable.references,
                (acquired as AppResult.Success).value,
            )
        if (completed is AppResult.Failed) return completed
        return AppResult.Success(
            CollaborationSpaceHistoryRecoveryAttempt.Original(
                history,
                stable,
                (completed as AppResult.Success).value,
            )
        )
    }

    private suspend fun publishedVisit(
        turn: CollaborationSpaceHistoryRecoveryTurn,
        row: CollaborationAccountIncomingHistoryLocator.Space,
    ): AppResult<CollaborationSpaceHistoryRecoveryAttempt> {
        val loaded = published(turn.runtime, turn.source, row)
        if (loaded is AppResult.Failed) return loaded
        val history =
            when (val found = (loaded as AppResult.Success).value) {
                is CollaborationSpaceKeyHistoryResolution.Ready -> found.history
                CollaborationSpaceKeyHistoryResolution.SourceUnavailable,
                CollaborationSpaceKeyHistoryResolution.MetadataUnavailable ->
                    return AppResult.Success(CollaborationSpaceHistoryRecoveryAttempt.Unavailable)
            }
        if (!matchesCollaborationSpaceHistoryRecoveryRoom(turn.recovery, history.traversal.roomId))
            return AppResult.Success(
                CollaborationSpaceHistoryRecoveryAttempt.PublishedOutsideTarget(history)
            )
        val loadedRead = readPublished(turn.runtime, history, turn.recovery.generation)
        if (loadedRead is AppResult.Failed) return loadedRead
        val read = (loadedRead as AppResult.Success).value
        if (read !is CollaborationHistoryRecoveryRead.Pending)
            return AppResult.Success(
                CollaborationSpaceHistoryRecoveryAttempt.Published(history, read)
            )
        if (read.references.events.size > 16) return pending("invalid_page", FailureKind.Validation)
        val stable =
            read.copy(references = read.references.copy(events = read.references.events.toList()))
        val acquired = acquirePublished(turn.runtime, history, stable.references)
        if (acquired is AppResult.Failed) return acquired
        val completed =
            completePublished(
                turn.runtime,
                history,
                stable.references,
                (acquired as AppResult.Success).value,
            )
        if (completed is AppResult.Failed) return completed
        return AppResult.Success(
            CollaborationSpaceHistoryRecoveryAttempt.Published(
                history,
                stable,
                (completed as AppResult.Success).value,
            )
        )
    }

    /**
     * Re-read once: an actually replaced turn may be reselected; unchanged failures stay failures.
     */
    private suspend fun settleContention(
        turn: CollaborationSpaceHistoryRecoveryTurn,
        failed: AppResult.Failed,
    ): AppResult<CollaborationSpaceHistoryRecoveryOutcome> {
        val checked = withTimeoutOrNull(5.seconds) { select(turn.runtime) } ?: return failed
        if (checked is AppResult.Failed) return checked
        return when (val current = (checked as AppResult.Success).value) {
            CollaborationSpaceHistoryRecoverySelection.Idle ->
                AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Obsolete)
            is CollaborationSpaceHistoryRecoverySelection.Deferred ->
                AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Deferred)
            is CollaborationSpaceHistoryRecoverySelection.Ready -> {
                if (
                    current.turn.runtime == turn.runtime &&
                        current.turn.source == turn.source &&
                        current.turn.registration == turn.registration &&
                        !sameCollaborationSpaceHistoryRecoveryTurn(
                            turn.recovery,
                            current.turn.recovery,
                        )
                )
                    AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Obsolete)
                else failed
            }
        }
    }

    private fun pending(suffix: String, kind: FailureKind) =
        AppResult.Failed(
            Failure(
                kind,
                "Messages are waiting to sync.",
                code = "client.collaboration_space_incoming_$suffix",
                retryAfterSeconds = 30,
            )
        )
}

enum class CollaborationSpaceHistoryRecoveryOutcome {
    Idle,
    Advanced,
    Deferred,
    Obsolete,
}
