package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Joint edit command, original preview and wake under one source/index/account transaction. */
interface CollaborationSpaceEditPublicationRepository {
    /** Null means concurrent source change; an absent source has a snapshot with null fields. */
    suspend fun readEditSource(
        access: CollaborationSpaceLocalAccess,
        source: CollaborationSpaceHistoryLocation,
        eventId: String,
    ): AppResult<CollaborationSpaceEditSourceSnapshot?>

    /**
     * New commands require a current authenticated canonical history source and inbox version.
     * Exact marked active/archive replay retains the original handoff without rewriting a newer
     * edit or renewing work. An unmarked old command is not proof of joint publication. A changed
     * source/index retains nothing. Caller owns source/admission policy and bounded reobservation.
     */
    suspend fun publishEdit(
        access: CollaborationSpaceLocalAccess,
        intent: CollaborationSpaceMessageIntent,
        source: CollaborationSpaceHistoryLocation,
        sourceRevision: String,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<CollaborationSpaceEditPublicationResult>
}
