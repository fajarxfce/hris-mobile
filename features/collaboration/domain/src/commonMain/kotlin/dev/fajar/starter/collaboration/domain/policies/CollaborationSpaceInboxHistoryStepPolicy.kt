package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Select one bounded action without I/O or retry. On conflict the owner must reacquire current
 * observations before selecting again; opaque trigger identities cannot establish freshness.
 */
fun nextCollaborationSpaceInboxHistoryStep(
    access: CollaborationSpaceLocalAccess,
    expected: CollaborationSpaceInboxHistoryCheckpoint?,
    trigger: CollaborationSpaceInboxHistoryTrigger,
    destination: CollaborationSpaceInboxVersion?,
    newPassId: String,
    recoverParked: Boolean,
): AppResult<CollaborationSpaceInboxHistoryStep> {
    val observation = CollaborationSpaceInboxHistoryChange.Observe(trigger, newPassId, destination)
    val proposed = collaborationSpaceInboxHistoryMutation(access, expected, observation)
    if (proposed is AppResult.Failed) return proposed
    val mutation = (proposed as AppResult.Success).value
    val retained = mutation.expected
    if (retained == null || retained.progress != mutation.replacement)
        return AppResult.Success(CollaborationSpaceInboxHistoryStep.Change(retained, observation))
    val pass =
        retained.progress.pass ?: return AppResult.Success(CollaborationSpaceInboxHistoryStep.Idle)
    val step =
        when (val traversal = pass.traversal) {
            CollaborationSpaceInboxHistoryTraversal.Parked -> {
                if (!recoverParked) CollaborationSpaceInboxHistoryStep.Idle
                else
                    CollaborationSpaceInboxHistoryStep.Change(
                        retained,
                        CollaborationSpaceInboxHistoryChange.Resume(destination),
                    )
            }
            CollaborationSpaceInboxHistoryTraversal.Ready ->
                CollaborationSpaceInboxHistoryStep.Change(
                    retained,
                    CollaborationSpaceInboxHistoryChange.Complete(destination),
                )
            is CollaborationSpaceInboxHistoryTraversal.Catalog -> {
                val first = traversal.batch?.remaining?.first()
                if (first == null)
                    CollaborationSpaceInboxHistoryStep.Catalog(retained, traversal.after)
                else CollaborationSpaceInboxHistoryStep.Visit(retained, first, pass.id)
            }
            is CollaborationSpaceInboxHistoryTraversal.Sources -> {
                val first = traversal.batch?.remaining?.first()
                if (first == null)
                    CollaborationSpaceInboxHistoryStep.Sources(retained, traversal.after)
                else CollaborationSpaceInboxHistoryStep.Visit(retained, first, pass.id)
            }
        }
    if (step is CollaborationSpaceInboxHistoryStep.Change) {
        val checked = collaborationSpaceInboxHistoryMutation(access, retained, step.change)
        if (checked is AppResult.Failed) return checked
    }
    return AppResult.Success(step)
}
