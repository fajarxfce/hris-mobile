package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHistorySource
import dev.fajar.starter.collaboration.data.mappers.toOriginalHistory
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.matrix.rustcomponents.sdk.Client

/** A finite original snapshot, not a live stream, complete provider history or sync cursor. */
class AndroidCollaborationHistoryWindow
internal constructor(
    val originals: List<CollaborationHistoryChange>,
    val unavailableEventIds: List<String>,
    val extractionNanos: Long,
    val modelCharacters: Long,
    val originalCharacters: Long,
) {
    override fun toString() = "AndroidCollaborationHistoryWindow([redacted])"
}

/** Uses only a known room. The admitted caller owns whether this network acquisition may start. */
internal class AndroidCollaborationHistoryWindows(
    private val client: Client,
    private val children: SdkChildResources,
    private val failure: () -> Failure?,
) {
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
            children.acquire(source, { safeAndroidCollaborationCall { it.open() } }, { it.stop() })
        if (acquired is AppResult.Failed) return acquired
        val child = (acquired as AppResult.Success).value
        val disposed: AppResult<Unit>
        val result =
            try {
                currentCoroutineContext().ensureActive()
                failure()?.let {
                    return AppResult.Failed(it)
                }
                safeStorageCall {
                    val snapshot = source.snapshot()
                    val originals = mutableListOf<CollaborationHistoryChange>()
                    val unavailable = mutableListOf<String>()
                    for (event in snapshot.events) {
                        val original = event.toOriginalHistory()
                        if (original == null) unavailable += event.eventId
                        else originals += original
                    }
                    AndroidCollaborationHistoryWindow(
                        originals.toList(),
                        unavailable.toList(),
                        snapshot.extractionNanos,
                        snapshot.modelCharacters,
                        snapshot.originalCharacters,
                    )
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
