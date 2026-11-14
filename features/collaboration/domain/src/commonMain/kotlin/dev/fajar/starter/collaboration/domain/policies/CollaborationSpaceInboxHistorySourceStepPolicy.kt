package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Finish older captured intervals before preparing a later pass. A visit attempts preparation and
 * each lane at most once, so repeated resets or a failing lane cannot monopolize the owner.
 */
fun nextCollaborationSpaceInboxHistorySourceStep(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    passId: String,
    destination: CollaborationSpaceInboxVersion?,
    current: CollaborationSpaceInboxHistorySourceCheckpoint?,
    attempted: Set<CollaborationSpaceInboxHistoryVisitOperation>,
): AppResult<CollaborationSpaceInboxHistorySourceStep> {
    if (
        !validCollaborationSpaceCatalogRequest(access, null, 16) ||
            !validCollaborationMessageId(conversationId) ||
            !validCollaborationMessageId(passId) ||
            !collaborationSpaceInboxHistoryDestinationCovers(destination, destination)
    )
        return historyProgressFailure(FailureKind.Validation)
    if (
        current != null &&
            (!validCollaborationSpaceInboxHistorySourceCheckpoint(access, current) ||
                current.progress.conversationId != conversationId)
    )
        return historyProgressFailure(FailureKind.Unexpected)
    val progress = current?.progress
    val needsPreparation =
        progress == null ||
            progress.destinationGeneration != destination?.generation ||
            progress.passId != passId &&
                progress.historyComplete &&
                progress.pendingWindow?.complete == true
    val step =
        when {
            needsPreparation -> {
                // A completed old interval yields before beginning its later interval in another
                // visit.
                if (attempted.isNotEmpty()) CollaborationSpaceInboxHistorySourceStep.Yield(current)
                else CollaborationSpaceInboxHistorySourceStep.Prepare(conversationId, passId)
            }
            else -> {
                val retained = requireNotNull(current)
                when {
                    !retained.progress.historyComplete &&
                        CollaborationSpaceInboxHistoryVisitOperation.History !in attempted ->
                        CollaborationSpaceInboxHistorySourceStep.History(retained)
                    retained.progress.pendingWindow?.complete != true &&
                        CollaborationSpaceInboxHistoryVisitOperation.Pending !in attempted ->
                        CollaborationSpaceInboxHistorySourceStep.Pending(retained)
                    else -> CollaborationSpaceInboxHistorySourceStep.Yield(retained)
                }
            }
        }
    if (
        step !is CollaborationSpaceInboxHistorySourceStep.Yield &&
            current?.version?.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER
    )
        return historyProgressFailure(FailureKind.Conflict)
    return AppResult.Success(step)
}
