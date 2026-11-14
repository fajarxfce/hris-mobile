package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * Executes one domain-selected local step under the existing repair owner's coroutine. Owns no
 * subscriptions, timers or transports. Every callback is an admitted use-case entry point.
 */
class CollaborationSpaceInboxHistoryTurnWorker(
    private val readProgress:
        suspend (CollaborationSpaceLocalAccess) -> AppResult<
                CollaborationSpaceInboxHistoryCheckpoint?
            >,
    private val changeProgress:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationSpaceInboxHistoryCheckpoint?,
            CollaborationSpaceInboxHistoryChange,
        ) -> AppResult<CollaborationSpaceInboxHistoryCheckpoint>,
    private val readCatalog:
        suspend (CollaborationSpaceLocalAccess, String?, Int) -> AppResult<
                CollaborationSpaceConversationCatalogRead
            >,
    private val readSources:
        suspend (CollaborationSpaceLocalAccess, String?, Int) -> AppResult<
                CollaborationSpaceInboxHistorySourcePage
            >,
    private val readSource:
        suspend (CollaborationSpaceLocalAccess, String) -> AppResult<
                CollaborationSpaceInboxHistorySourceCheckpoint?
            >,
    private val prepareSource:
        suspend (CollaborationSpaceLocalAccess, String, String) -> AppResult<
                CollaborationSpaceInboxHistorySourceCheckpoint
            >,
    private val readHistory:
        suspend (
            CollaborationSpaceLocalAccess, String, CollaborationHistoryChangeCursor?, Int,
        ) -> AppResult<CollaborationSpaceHistoryChangesRead>,
    private val confirmHistory:
        suspend (
            CollaborationSpaceLocalAccess,
            String,
            CollaborationHistoryChangeCursor?,
            CollaborationSpaceHistoryChangesRead,
        ) -> AppResult<CollaborationSpaceInboxHistoryCommit>,
    private val publishHistory:
        suspend (CollaborationSpaceLocalAccess, String, List<String>) -> AppResult<
                CollaborationSpaceInboxHistoryCommit
            >,
    private val retainHistory:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationSpaceInboxHistorySourceCheckpoint,
            CollaborationSpaceHistoryChangesRead,
            CollaborationSpaceInboxHistoryCommit,
        ) -> AppResult<CollaborationSpaceInboxHistorySourceCheckpoint>,
    private val restartSource:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationSpaceInboxHistorySourceCheckpoint,
            CollaborationSpaceHistoryChangesRead,
        ) -> AppResult<CollaborationSpaceInboxHistorySourceCheckpoint>,
    private val readPending:
        suspend (
            CollaborationSpaceLocalAccess, CollaborationSpaceInboxHistorySourceCheckpoint, Int,
        ) -> AppResult<CollaborationSpaceInboxHistoryPendingPage>,
    private val completePending:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationSpaceInboxHistorySourceCheckpoint,
            CollaborationSpaceInboxHistoryPendingPage,
        ) -> AppResult<CollaborationSpaceInboxHistorySourceCheckpoint>,
    private val retainPending:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationSpaceInboxHistorySourceCheckpoint,
            CollaborationSpaceInboxHistoryPendingPage,
            CollaborationSpaceInboxHistoryCommit,
        ) -> AppResult<CollaborationSpaceInboxHistorySourceCheckpoint>,
) {
    sealed interface Outcome {
        data object Idle : Outcome

        data class Advanced(
            val checkpoint: CollaborationSpaceInboxHistoryCheckpoint,
            val deferredFailure: Failure?,
        ) : Outcome
    }

    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        trigger: CollaborationSpaceInboxHistoryTrigger,
        destination: CollaborationSpaceInboxVersion?,
        newPassId: String,
        recoverParked: Boolean,
    ): AppResult<Outcome> {
        currentCoroutineContext().ensureActive()
        val read = readProgress(access)
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val selected =
            nextCollaborationSpaceInboxHistoryStep(
                access,
                (read as AppResult.Success).value,
                trigger,
                destination,
                newPassId,
                recoverParked,
            )
        if (selected is AppResult.Failed) return selected
        val expected: CollaborationSpaceInboxHistoryCheckpoint?
        val change: CollaborationSpaceInboxHistoryChange
        var deferred: Failure? = null
        when (val step = (selected as AppResult.Success).value) {
            CollaborationSpaceInboxHistoryStep.Idle -> return AppResult.Success(Outcome.Idle)
            is CollaborationSpaceInboxHistoryStep.Change -> {
                expected = step.expected
                change = step.change
            }
            is CollaborationSpaceInboxHistoryStep.Catalog -> {
                expected = step.expected
                val page = readCatalog(access, step.after, 16)
                currentCoroutineContext().ensureActive()
                if (page is AppResult.Failed) return page
                change =
                    when (val value = (page as AppResult.Success).value) {
                        CollaborationSpaceConversationCatalogRead.Restart ->
                            CollaborationSpaceInboxHistoryChange.RestartCatalog(destination)
                        is CollaborationSpaceConversationCatalogRead.Page ->
                            CollaborationSpaceInboxHistoryChange.CaptureCatalog(value, destination)
                    }
            }
            is CollaborationSpaceInboxHistoryStep.Sources -> {
                expected = step.expected
                val page = readSources(access, step.after, 16)
                currentCoroutineContext().ensureActive()
                if (page is AppResult.Failed) return page
                change =
                    CollaborationSpaceInboxHistoryChange.CaptureSources(
                        (page as AppResult.Success).value,
                        destination,
                    )
            }
            is CollaborationSpaceInboxHistoryStep.Visit -> {
                expected = step.expected
                val visited = visit(access, step.conversationId, step.passId, destination)
                if (visited is AppResult.Failed) return visited
                val value = (visited as AppResult.Success).value
                deferred = value.failure
                change =
                    CollaborationSpaceInboxHistoryChange.AcknowledgeSource(
                        step.conversationId,
                        value.before,
                        value.after,
                        destination,
                    )
            }
        }
        currentCoroutineContext().ensureActive()
        val saved = changeProgress(access, expected, change)
        currentCoroutineContext().ensureActive()
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success -> AppResult.Success(Outcome.Advanced(saved.value, deferred))
        }
    }

    /** All operation attempts belong to this visit; a failed attempt consumes its lane budget. */
    private suspend fun visit(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        passId: String,
        destination: CollaborationSpaceInboxVersion?,
    ): AppResult<VisitResult> {
        val read = readSource(access, conversationId)
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed)
            return if (collaborationSpaceDirectoryResponseClosesRead(read.failure)) read
            else AppResult.Success(VisitResult(null, null, read.failure))
        val before = (read as AppResult.Success).value
        var current = before
        var failure: Failure? = null
        val attempted = mutableSetOf<CollaborationSpaceInboxHistoryVisitOperation>()
        repeat(CollaborationSpaceInboxHistoryVisitOperation.entries.size + 1) {
            currentCoroutineContext().ensureActive()
            val selected =
                nextCollaborationSpaceInboxHistorySourceStep(
                    access,
                    conversationId,
                    passId,
                    destination,
                    current,
                    attempted,
                )
            if (selected is AppResult.Failed)
                return AppResult.Success(VisitResult(before, null, selected.failure))
            val result =
                when (val step = (selected as AppResult.Success).value) {
                    is CollaborationSpaceInboxHistorySourceStep.Yield -> {
                        return AppResult.Success(VisitResult(before, step.retained, failure))
                    }
                    is CollaborationSpaceInboxHistorySourceStep.Prepare -> {
                        attempted += CollaborationSpaceInboxHistoryVisitOperation.Prepare
                        prepareSource(access, step.conversationId, step.passId)
                    }
                    is CollaborationSpaceInboxHistorySourceStep.History -> {
                        attempted += CollaborationSpaceInboxHistoryVisitOperation.History
                        historyPage(access, step.expected)
                    }
                    is CollaborationSpaceInboxHistorySourceStep.Pending -> {
                        attempted += CollaborationSpaceInboxHistoryVisitOperation.Pending
                        pendingPage(access, step.expected)
                    }
                }
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Failed -> {
                    if (collaborationSpaceDirectoryResponseClosesRead(result.failure)) return result
                    failure = failure ?: result.failure
                    // Inspect once after this failed operation; never replay its uncertain write.
                    // The other lane can then use the actual retained version.
                    val inspected = readSource(access, conversationId)
                    currentCoroutineContext().ensureActive()
                    if (inspected is AppResult.Failed) {
                        if (collaborationSpaceDirectoryResponseClosesRead(inspected.failure))
                            return inspected
                        return AppResult.Success(VisitResult(before, null, failure))
                    }
                    current = (inspected as AppResult.Success).value
                }
                is AppResult.Success -> current = result.value
            }
            yield()
        }
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    }

    /** The domain page policy permits one reset, then one fresh bounded read. */
    private suspend fun historyPage(
        access: CollaborationSpaceLocalAccess,
        initial: CollaborationSpaceInboxHistorySourceCheckpoint,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint> {
        var current = initial
        var restarted = false
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val read =
                readHistory(
                    access,
                    current.progress.conversationId,
                    current.progress.historyCursor,
                    16,
                )
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return read
            val selected =
                collaborationSpaceInboxHistoryPageStep(
                    access,
                    current,
                    (read as AppResult.Success).value,
                    restarted,
                )
            if (selected is AppResult.Failed) return selected
            when (val step = (selected as AppResult.Success).value) {
                is CollaborationSpaceInboxHistoryPageStep.Restart -> {
                    val reset = restartSource(access, step.expected, step.read)
                    currentCoroutineContext().ensureActive()
                    if (reset is AppResult.Failed) return reset
                    current = (reset as AppResult.Success).value
                    restarted = true
                    yield()
                }
                is CollaborationSpaceInboxHistoryPageStep.Confirm -> {
                    val confirmed =
                        confirmHistory(
                            access,
                            step.expected.progress.conversationId,
                            step.expected.progress.historyCursor,
                            step.read,
                        )
                    currentCoroutineContext().ensureActive()
                    if (confirmed is AppResult.Failed) return confirmed
                    return retainHistory(
                        access,
                        step.expected,
                        step.read,
                        (confirmed as AppResult.Success).value,
                    )
                }
                is CollaborationSpaceInboxHistoryPageStep.PublishHistory -> {
                    val published =
                        publishHistory(access, step.expected.progress.conversationId, step.eventIds)
                    currentCoroutineContext().ensureActive()
                    if (published is AppResult.Failed) return published
                    return retainHistory(
                        access,
                        step.expected,
                        step.read,
                        (published as AppResult.Success).value,
                    )
                }
                else ->
                    return collaborationRecoveryFailure(
                        "client.collaboration_invalid_response",
                        FailureKind.Unexpected,
                    )
            }
        }
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    }

    private suspend fun pendingPage(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxHistorySourceCheckpoint,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint> {
        val read = readPending(access, expected, 16)
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val selected =
            collaborationSpaceInboxHistoryPendingStep(
                access,
                expected,
                (read as AppResult.Success).value,
            )
        if (selected is AppResult.Failed) return selected
        return when (val step = (selected as AppResult.Success).value) {
            is CollaborationSpaceInboxHistoryPageStep.CompletePending ->
                completePending(access, step.expected, step.read)
            is CollaborationSpaceInboxHistoryPageStep.PublishPending -> {
                val published =
                    publishHistory(access, step.expected.progress.conversationId, step.eventIds)
                currentCoroutineContext().ensureActive()
                if (published is AppResult.Failed) return published
                retainPending(
                    access,
                    step.expected,
                    step.read,
                    (published as AppResult.Success).value,
                )
            }
            else ->
                collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
        }
    }

    private data class VisitResult(
        val before: CollaborationSpaceInboxHistorySourceCheckpoint?,
        val after: CollaborationSpaceInboxHistorySourceCheckpoint?,
        val failure: Failure?,
    )
}
