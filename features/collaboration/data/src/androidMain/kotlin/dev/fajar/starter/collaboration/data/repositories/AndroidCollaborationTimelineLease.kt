package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationTimelineSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toDomain
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.flow.*

/** Platform data handle. Admission and UI projection are coordinated above the repository. */
class AndroidCollaborationTimelineLease
internal constructor(
    override val owner: CollaborationOwner,
    override val deviceId: String,
    override val matrixRoomId: String,
    private val child: SdkChildLease<AndroidCollaborationTimelineSource>,
    source: StateFlow<CollaborationSdkTimelineDto>,
    syncFailures: StateFlow<Failure?>,
) : CollaborationTimelineLease {
    override val updates: Flow<AppResult<List<CollaborationTimelineItem>>> =
        source.combine(
            syncFailures
                .map {
                    it?.takeIf { failure ->
                        failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
                    }
                }
                .distinctUntilChanged()
        ) { snapshot, denied ->
            if (denied != null) AppResult.Failed(denied)
            else
                when (snapshot.status) {
                    SdkTimelineStatus.READY ->
                        safeStorageCall { snapshot.items.map { it.toDomain() } }
                    SdkTimelineStatus.INVALID ->
                        unavailable("client.collaboration_timeline_invalid")
                    SdkTimelineStatus.CLOSED -> unavailable("client.collaboration_timeline_closed")
                }
        }

    private fun unavailable(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat timeline is unavailable.", code = code)
        )

    override suspend fun close(): AppResult<Unit> = child.close()

    override fun toString() = "AndroidCollaborationTimelineLease([redacted])"
}
