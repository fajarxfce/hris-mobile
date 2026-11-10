package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationSpaceRecoveryOwner(owner: CollaborationSpaceOwner): Boolean =
    validCollaborationOriginalConversationOwner(owner) &&
        validCollaborationSpaceAccountAccess(owner.parent.localSpaceAccountAccess())

private fun validBoundary(
    owner: CollaborationSpaceOwner,
    b: CollaborationSpaceRoomBindingBoundary,
) =
    b.audience == owner.parent.audience &&
        b.accountId == owner.parent.accountId &&
        b.spaceId == owner.spaceId &&
        validCollaborationMessageId(b.generation) &&
        b.revision in 0..9_007_199_254_740_991L

private fun validAfter(id: String) = id.matches(Regex("b-[0-9a-f]{64}"))

fun validCollaborationSpaceRecoverySweep(
    owner: CollaborationSpaceOwner,
    s: CollaborationSpaceRecoverySweep,
): Boolean {
    if (
        !validCollaborationSpaceRecoveryOwner(owner) ||
            !validCollaborationMessageId(s.wakeGeneration)
    )
        return false
    if (s.boundary == null) {
        if (
            s.afterId != null ||
                s.pending?.conversationId != null ||
                s.pending?.nextAfterId != null ||
                s.hasNewerBindings
        )
            return false
    } else if (!validBoundary(owner, s.boundary)) return false
    if (s.afterId != null && !validAfter(s.afterId)) return false
    if (s.reachedEnd && s.pending != null) return false
    if (s.messages?.let(::validCollaborationSpaceMessageRecoverySweep) == false) return false
    if (s.pending != null && s.messages?.pending != null) return false
    val claim = s.pending ?: return true
    if (claim.conversationId != null && !validCollaborationMessageId(claim.conversationId))
        return false
    val next = claim.nextAfterId ?: return true
    return validAfter(next) && (s.afterId == null || next > s.afterId)
}

fun validCollaborationSpaceRecoveryProgress(
    owner: CollaborationSpaceOwner,
    p: CollaborationSpaceRecoveryProgress,
): Boolean =
    validCollaborationSpaceRecoveryOwner(owner) &&
        validCollaborationMessageId(p.generation) &&
        (p.sweep == null || validCollaborationSpaceRecoverySweep(owner, p.sweep))

internal fun nextCollaborationSpaceRecoverySweep(
    snapshot: CollaborationSpaceRecoverySnapshot,
    change: CollaborationSpaceRecoveryChange,
): AppResult<CollaborationSpaceRecoverySweep?> {
    val owner = snapshot.owner
    val progress = snapshot.progress
    if (
        !validCollaborationSpaceRecoveryOwner(owner) ||
            (progress != null && !validCollaborationSpaceRecoveryProgress(owner, progress))
    )
        return invalidSpaceProgress()
    val current = progress?.sweep
    val next =
        when (change) {
            is CollaborationSpaceRecoveryChange.Begin -> {
                if (!validCollaborationMessageId(change.wake.generation))
                    return invalidSpaceProgress()
                current ?: CollaborationSpaceRecoverySweep(change.wake.generation)
            }
            is CollaborationSpaceRecoveryChange.Attempt -> {
                val claimed = claimCollaborationSpaceRecoveryBinding(owner, current, change.page)
                if (claimed is AppResult.Failed) return claimed
                (claimed as AppResult.Success).value
            }
            is CollaborationSpaceRecoveryChange.Complete -> {
                val claim = current?.pending ?: return invalidSpaceProgress()
                current.copy(
                    afterId = claim.nextAfterId ?: current.afterId,
                    pending = null,
                    reachedEnd = claim.nextAfterId == null,
                    retryRequired = current.retryRequired || change.retryRequired,
                    continuationRequired =
                        current.continuationRequired || change.continuationRequired,
                )
            }
            is CollaborationSpaceRecoveryChange.AttemptMessages -> {
                if (current == null || current.pending != null) return invalidSpaceProgress()
                val claimed =
                    claimCollaborationSpaceRecoveryMessage(
                        owner.localDirectoryAccess(),
                        current.messages ?: CollaborationSpaceMessageRecoverySweep(),
                        change.records,
                    ) ?: return invalidSpaceProgress()
                current.copy(messages = claimed)
            }
            is CollaborationSpaceRecoveryChange.CompleteMessage -> {
                val messages = current?.messages ?: return invalidSpaceProgress()
                val claim = messages.pending ?: return invalidSpaceProgress()
                current.copy(
                    messages = messages.copy(after = claim.position, pending = null),
                    retryRequired = current.retryRequired || change.retryRequired,
                )
            }
            CollaborationSpaceRecoveryChange.Finish -> {
                if (
                    current == null ||
                        !current.reachedEnd ||
                        current.pending != null ||
                        current.messages?.let { !it.reachedEnd || it.pending != null } == true
                )
                    return invalidSpaceProgress()
                if (
                    current.retryRequired ||
                        current.continuationRequired ||
                        current.hasNewerBindings
                )
                    CollaborationSpaceRecoverySweep(current.wakeGeneration)
                else null
            }
        }
    if (next != null && !validCollaborationSpaceRecoverySweep(owner, next))
        return invalidSpaceProgress()
    return AppResult.Success(next)
}

/** A raw binding page has its own immutable boundary and cannot replace a message claim. */
private fun claimCollaborationSpaceRecoveryBinding(
    owner: CollaborationSpaceOwner,
    current: CollaborationSpaceRecoverySweep?,
    p: CollaborationSpaceRoomBindingPage,
): AppResult<CollaborationSpaceRecoverySweep> {
    if (
        current == null ||
            current.pending != null ||
            current.reachedEnd ||
            current.messages?.pending != null ||
            p.bindings.size > 1 ||
            (current.boundary != null && p.boundary != current.boundary) ||
            (p.boundary == null &&
                (p.bindings.isNotEmpty() || p.next != null || p.hasNewerBindings)) ||
            (p.boundary != null && !validBoundary(owner, p.boundary)) ||
            (p.next != null && p.next.boundary != p.boundary) ||
            p.bindings.any { !validCollaborationSpaceRoomBinding(owner.localDirectoryAccess(), it) }
    )
        return invalidSpaceProgress()
    return AppResult.Success(
        current.copy(
            boundary = p.boundary,
            pending =
                CollaborationSpaceRecoveryClaim(
                    p.bindings.singleOrNull()?.conversation?.id,
                    p.next?.afterId,
                ),
            hasNewerBindings = current.hasNewerBindings || p.hasNewerBindings,
        )
    )
}

private fun invalidSpaceProgress() =
    collaborationRecoveryFailure(
        "client.collaboration_invalid_space_recovery_progress",
        FailureKind.Validation,
    )
