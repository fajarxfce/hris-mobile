package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Actual Company history can supply a canonical Group inbox without copying its content. */
interface CollaborationCompanyHistorySnapshotRepository {
    suspend fun snapshot(
        source: CollaborationSpaceHistoryLocation.Original,
        eventIds: List<String>,
    ): AppResult<CollaborationSpaceHistorySnapshot?>

    /** Source and destination keep independent namespaces within the same local account. */
    suspend fun publishInbox(
        access: CollaborationSpaceLocalAccess,
        source: CollaborationSpaceHistoryLocation.Original,
        sourceRevision: String,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<Boolean>
}
