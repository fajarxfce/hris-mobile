package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

const val COLLABORATION_SPACE_DIRECTORY_CAPACITY = 10_000
const val COLLABORATION_SPACE_DIRECTORY_PAGE_SIZE = 50

fun validCollaborationSpaceDirectoryCheckpoint(
    value: CollaborationSpaceDirectoryCheckpoint
): Boolean =
    validCollaborationMessageId(value.generation) &&
        value.revision in 0..9_007_199_254_740_991L &&
        value.count in 0..COLLABORATION_SPACE_DIRECTORY_CAPACITY &&
        value.startedAt.toEpochMilliseconds() >= 0 &&
        (value.acquiredAt == null || value.acquiredAt.toEpochMilliseconds() >= 0) &&
        (value.nextCursor == null || validCollaborationMessageId(value.nextCursor)) &&
        if (value.revision == 0L) {
            value.count == 0 &&
                value.acquiredAt == null &&
                value.nextCursor == null &&
                !value.complete &&
                !value.published
        } else {
            value.acquiredAt != null &&
                value.complete == (value.nextCursor == null) &&
                (value.complete || value.count in 1 until COLLABORATION_SPACE_DIRECTORY_CAPACITY) &&
                value.published == (value.complete || value.publishPartial)
        }

/** Group directory acquisition only; a provider outage is not an account-access denial. */
fun collaborationSpaceDirectoryResponseClosesRead(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in setOf("client_update_required", "client_version_required")

fun validCollaborationSpaceDirectoryAdmission(
    value: CollaborationSpaceDirectoryAdmission
): Boolean =
    if (value.denial == null) value.revision == null
    else
        value.revision != null &&
            validCollaborationMessageId(value.revision) &&
            collaborationSpaceDirectoryResponseClosesRead(value.denial)

/** Pure local filtering; no network acquisition, indexing of names or read-admission changes. */
fun selectCollaborationSpaceDirectory(
    snapshot: CollaborationSpaceDirectorySnapshot,
    query: String,
): List<CollaborationSpaceContact> {
    require(query.length <= 80 && query.none { it.code in 0..31 || it.code in 127..159 })
    require(snapshot.contacts.size <= COLLABORATION_SPACE_DIRECTORY_CAPACITY)
    require(
        snapshot.checkpoint?.count == snapshot.contacts.size ||
            (snapshot.checkpoint == null && snapshot.contacts.isEmpty())
    )
    val text = query.trim()
    return snapshot.contacts
        .asSequence()
        .filter { text.isEmpty() || it.displayName.contains(text, ignoreCase = true) }
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.accountId }))
        .toList()
}
