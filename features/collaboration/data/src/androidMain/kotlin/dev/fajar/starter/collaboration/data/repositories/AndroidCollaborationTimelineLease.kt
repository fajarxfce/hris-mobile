package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationTimelineSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toDomain
import dev.fajar.starter.collaboration.domain.entities.CollaborationTimelineItem
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.flow.*

/** Platform data handle. Admission and UI projection are coordinated above the repository. */
class AndroidCollaborationTimelineLease
internal constructor(
    private val child: SdkChildLease<AndroidCollaborationTimelineSource>,
    source: StateFlow<CollaborationSdkTimelineDto>,
) {
    val updates: Flow<AppResult<List<CollaborationTimelineItem>>> =
        source.map { snapshot ->
            when (snapshot.status) {
                SdkTimelineStatus.READY -> safeStorageCall { snapshot.items.map { it.toDomain() } }
                SdkTimelineStatus.INVALID -> unavailable("client.collaboration_timeline_invalid")
                SdkTimelineStatus.CLOSED -> unavailable("client.collaboration_timeline_closed")
            }
        }

    private fun unavailable(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat timeline is unavailable.", code = code)
        )

    suspend fun close(): AppResult<Unit> = child.close()

    override fun toString() = "AndroidCollaborationTimelineLease([redacted])"
}
