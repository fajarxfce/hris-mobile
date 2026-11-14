package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * One collected local account/Group lifetime; no native client, network callback or retry timer.
 */
class CollaborationSpaceRecipientTransferWorker(
    private val source:
        (CollaborationSpaceLocalAccess) -> Flow<
                AppResult<CollaborationSpaceRecipientOutboxVersion?>
            >,
    private val catalog:
        (CollaborationSpaceLocalAccess) -> Flow<
                AppResult<CollaborationSpaceConversationCatalogVersion?>
            >,
    private val destination:
        (CollaborationSpaceLocalAccess) -> Flow<AppResult<CollaborationSpaceInboxVersion?>>,
    private val readPage:
        suspend (CollaborationSpaceLocalAccess, String?, Int) -> AppResult<
                CollaborationSpaceRecipientSubmissionPage
            >,
    private val transfer:
        suspend (CollaborationSpaceLocalAccess, CollaborationSpaceRecipientSubmission) -> AppResult<
                CollaborationSpaceRecipientTransfer
            >,
) {
    operator fun invoke(access: CollaborationSpaceLocalAccess): Flow<AppResult<Unit>> =
        flow {
                coroutineScope {
                    val inputs = MutableStateFlow(Inputs())
                    val observations =
                        listOf(
                            launch {
                                source(access).collect { value ->
                                    inputs.update { it.copy(source = value) }
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
                    val attempts = linkedMapOf<String, CollaborationSpaceRecipientTransferAttempt>()
                    var consumed: Inputs? = null
                    var emptySource: CollaborationSpaceRecipientOutboxVersion? = null
                    var scannedSource: CollaborationSpaceRecipientOutboxVersion? = null
                    try {
                        while (true) {
                            val current = inputs.first { it != consumed }
                            consumed = current
                            when (val prepared = current.frame()) {
                                null -> Unit
                                is AppResult.Failed -> emit(prepared)
                                is AppResult.Success -> {
                                    val frame = prepared.value
                                    val currentSource = frame.source
                                    if (
                                        currentSource == null ||
                                            currentSource.revision == null ||
                                            frame.source == emptySource
                                    ) {
                                        attempts.clear()
                                        emptySource = frame.source
                                        emit(AppResult.Success(Unit))
                                    } else if (
                                        frame.source == scannedSource &&
                                            attempts.values.none {
                                                shouldAttemptCollaborationSpaceRecipientTransfer(
                                                    it.submission,
                                                    frame,
                                                    it,
                                                )
                                            }
                                    ) {
                                        emit(AppResult.Success(Unit))
                                    } else {
                                        // Own source writes are coalesced into a later frame, never
                                        // used
                                        // to cancel this pass and restart at its first blocked
                                        // recipient.
                                        when (val result = Pass(access, frame, attempts).run()) {
                                            is AppResult.Failed -> emit(result)
                                            is AppResult.Success -> {
                                                scannedSource = frame.source
                                                if (result.value) emptySource = frame.source
                                                emit(AppResult.Success(Unit))
                                            }
                                        }
                                    }
                                }
                            }
                            yield()
                        }
                    } finally {
                        observations.forEach { it.cancel() }
                        // coroutineScope joins every subscription before ownership can be replaced.
                    }
                }
            }
            .distinctUntilChanged()

    /** This finite page cursor shares only bounded attempt memory with the collected lifetime. */
    private inner class Pass(
        val access: CollaborationSpaceLocalAccess,
        val frame: CollaborationSpaceRecipientTransferFrame,
        val attempts: MutableMap<String, CollaborationSpaceRecipientTransferAttempt>,
    ) {
        suspend fun run(): AppResult<Boolean> {
            var cursor: String? = null
            val seen = mutableSetOf<String>()
            var failure: Failure? = null
            repeat(
                COLLABORATION_SPACE_RECIPIENT_CAPACITY / COLLABORATION_SPACE_RECIPIENT_PAGE_LIMIT
            ) {
                currentCoroutineContext().ensureActive()
                val read = readPage(access, cursor, COLLABORATION_SPACE_RECIPIENT_PAGE_LIMIT)
                currentCoroutineContext().ensureActive()
                if (read is AppResult.Failed) return read
                val captured =
                    captureCollaborationSpaceRecipientPending(
                        access,
                        cursor,
                        COLLABORATION_SPACE_RECIPIENT_PAGE_LIMIT,
                        (read as AppResult.Success).value,
                    )
                if (captured is AppResult.Failed) return captured
                val page = (captured as AppResult.Success).value
                for (submission in page.submissions) {
                    currentCoroutineContext().ensureActive()
                    if (
                        !seen.add(submission.id) ||
                            seen.size > COLLABORATION_SPACE_RECIPIENT_CAPACITY
                    )
                        return invalidPage()
                    val previous = attempts[submission.id]
                    val result =
                        if (
                            shouldAttemptCollaborationSpaceRecipientTransfer(
                                submission,
                                frame,
                                previous,
                            )
                        ) {
                            val result = transfer(access, submission)
                            currentCoroutineContext().ensureActive()
                            if (
                                submission.id !in attempts &&
                                    attempts.size == COLLABORATION_SPACE_RECIPIENT_CAPACITY
                            ) {
                                val stale =
                                    attempts.keys.firstOrNull { it !in seen }
                                        ?: return invalidPage()
                                attempts.remove(stale)
                            }
                            attempts[submission.id] =
                                CollaborationSpaceRecipientTransferAttempt(
                                    submission,
                                    frame,
                                    result,
                                )
                            result
                        } else requireNotNull(previous).result
                    if (result is AppResult.Failed) failure = failure ?: result.failure
                    yield()
                }
                if (page.next == null) {
                    attempts.keys.retainAll(seen)
                    return failure?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(seen.isEmpty())
                }
                cursor = page.next
                yield()
            }
            return invalidPage()
        }

        private fun invalidPage() =
            collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
    }

    private data class Inputs(
        val source: AppResult<CollaborationSpaceRecipientOutboxVersion?>? = null,
        val catalog: AppResult<CollaborationSpaceConversationCatalogVersion?>? = null,
        val destination: AppResult<CollaborationSpaceInboxVersion?>? = null,
    ) {
        fun frame(): AppResult<CollaborationSpaceRecipientTransferFrame>? {
            if (source is AppResult.Failed) return source
            if (catalog is AppResult.Failed) return catalog
            if (destination is AppResult.Failed) return destination
            val currentSource = (source as? AppResult.Success)?.value
            if (source == null || catalog == null || destination == null) return null
            val frame =
                CollaborationSpaceRecipientTransferFrame(
                    currentSource,
                    (catalog as AppResult.Success).value,
                    (destination as AppResult.Success).value,
                )
            return if (validCollaborationSpaceRecipientTransferFrame(frame))
                AppResult.Success(frame)
            else
                collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
        }
    }
}
