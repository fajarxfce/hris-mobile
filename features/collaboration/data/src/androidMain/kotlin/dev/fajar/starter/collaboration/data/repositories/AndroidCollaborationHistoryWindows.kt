package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHistorySource
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.data.mappers.toMessageEdit
import dev.fajar.starter.collaboration.data.mappers.toOriginalHistory
import dev.fajar.starter.collaboration.domain.entities.CollaborationEventSnapshot
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageEdit
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.RequestFailureException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client

/** A finite original snapshot, not a live stream, complete provider history or sync cursor. */
@ConsistentCopyVisibility
data class AndroidCollaborationHistoryWindow
internal constructor(
    val originals: List<CollaborationHistoryChange>,
    val unavailableEventIds: List<String>,
    val extractionNanos: Long,
    val modelCharacters: Long,
    val originalCharacters: Long,
    val edits: List<CollaborationMessageEdit> = emptyList(),
    val replacementCharacters: Long = 0L,
    val acquiredAnchor: CollaborationEventSnapshot? = null,
) {
    override fun toString() = "AndroidCollaborationHistoryWindow([redacted])"
}

/** Uses only a known room. The admitted caller owns whether this network acquisition may start. */
internal class AndroidCollaborationHistoryWindows(
    private val client: Client,
    private val children: SdkChildResources,
    private val decodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val focuses: SdkHistoryFocuses = SdkHistoryFocuses(),
    private val failure: () -> Failure?,
) {
    suspend fun needsReopen(roomId: String, anchorEventId: String) =
        focuses.needsReopen(roomId, anchorEventId)

    suspend fun read(
        roomId: String,
        anchorEventId: String,
    ): AppResult<AndroidCollaborationHistoryWindow> {
        failure()?.let {
            return AppResult.Failed(it)
        }
        if (
            !validCollaborationEventId(anchorEventId) ||
                !roomId.startsWith('!') ||
                roomId.length !in 2..255 ||
                roomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid history window."))
        val source =
            AndroidCollaborationHistorySource(anchorEventId) {
                requireNotNull(client.getRoom(roomId))
            }
        val acquired =
            children.acquire(
                source,
                {
                    safeAndroidCollaborationCall(AndroidCollaborationOperation.HistoryWindow) {
                        if (!focuses.reserve(roomId, anchorEventId))
                            throw RequestFailureException(
                                Failure(
                                    FailureKind.Unavailable,
                                    "Chat history needs to reopen.",
                                    code = "client.collaboration_history_focus_capacity",
                                )
                            )
                        it.open()
                    }
                },
                { it.stop() },
            )
        if (acquired is AppResult.Failed) {
            currentCoroutineContext().ensureActive()
            return failure()?.let {
                AppResult.Failed(collaborationSdkAcquisitionFailure(acquired, it))
            } ?: acquired
        }
        val child = (acquired as AppResult.Success).value
        val disposed: AppResult<Unit>
        val result =
            try {
                currentCoroutineContext().ensureActive()
                failure()?.let {
                    return AppResult.Failed(it)
                }
                safeAndroidCollaborationCall(AndroidCollaborationOperation.HistoryWindow) {
                    withContext(decodingDispatcher) {
                        val snapshot = source.snapshot()
                        val originals = mutableListOf<CollaborationHistoryChange>()
                        val unavailable = mutableListOf<String>()
                        for (event in snapshot.events) {
                            val original = event.toOriginalHistory()
                            if (original == null) unavailable += event.eventId
                            else originals += original
                        }
                        val targets =
                            originals
                                .filterIsInstance<CollaborationHistoryChange.Text>()
                                .map { it.eventId }
                                .toSet()
                        val edits =
                            snapshot.replacements.mapNotNull { replacement ->
                                if (replacement.targetEventId !in targets) null
                                else
                                    try {
                                        replacement.toMessageEdit()
                                    } catch (_: IllegalArgumentException) {
                                        // Unsupported/malformed replacement content must not
                                        // suppress a
                                        // valid original. Cancellation and storage/crypto faults
                                        // propagate.
                                        null
                                    }
                            }
                        AndroidCollaborationHistoryWindow(
                            originals.toList(),
                            unavailable.toList(),
                            snapshot.extractionNanos,
                            snapshot.modelCharacters,
                            snapshot.originalCharacters,
                            edits,
                            snapshot.replacementCharacters,
                        )
                    }
                }
            } finally {
                disposed = child.close()
            }
        currentCoroutineContext().ensureActive()
        failure()?.let {
            return AppResult.Failed(it)
        }
        if (disposed is AppResult.Failed) return disposed
        return result
    }
}
