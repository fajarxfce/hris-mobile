@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.policies.documentFailure
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A current header and its bounded history response must describe compatible immutable facts. */
fun mergeDocumentMetadataPage(
    previous: DocumentMetadataCheckpoint?,
    head: DocumentMetadataHead,
    page: DocumentMetadataPage,
    after: Long?,
    refreshedAt: Instant,
    generation: String,
): AppResult<DocumentMetadataCheckpoint> {
    if (previous != null && !compatibleDocumentMetadataHeads(previous.head, head))
        return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
    if (previous?.head?.metadataSupported == true && !head.metadataSupported)
        return documentFailure("client.document_metadata_unsupported", FailureKind.Unavailable)
    if (!validDocumentMetadataPage(head, page, after))
        return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
    if ((after ?: -1L) > (previous?.historyThrough ?: -1L))
        return documentFailure("client.document_metadata_history_gap", FailureKind.Conflict)
    if (Uuid.parseOrNull(generation)?.toString() != generation)
        return documentFailure("client.document_metadata_invalid")
    val through = maxOf(previous?.historyThrough ?: -1L, page.items.lastOrNull()?.version ?: -1L)
    val next =
        DocumentMetadataCheckpoint(
            previous?.generation ?: generation,
            (previous?.revision ?: -1L) + 1,
            head,
            refreshedAt,
            through.takeIf { it >= 0 },
        )
    return AppResult.Success(next)
}

fun compatibleDocumentMetadataHeads(
    old: DocumentMetadataHead,
    next: DocumentMetadataHead,
): Boolean =
    old.id == next.id &&
        old.employmentId == next.employmentId &&
        old.createdBy == next.createdBy &&
        old.createdAt == next.createdAt &&
        next.version >= old.version &&
        (old.metadataVersion == null ||
            next.metadataVersion == null ||
            next.metadataVersion >= old.metadataVersion) &&
        (old.metadataVersion == null ||
            next.metadataVersion != old.metadataVersion ||
            next.metadata == old.metadata) &&
        (old.version != next.version ||
            old.metadata == null ||
            next.metadata == null ||
            (old.metadataVersion == next.metadataVersion && old.metadata == next.metadata))

fun validDocumentMetadataPage(
    head: DocumentMetadataHead,
    page: DocumentMetadataPage,
    after: Long?,
): Boolean {
    if (after != null && after !in 0..DOCUMENT_METADATA_MAXIMUM_VERSION) return false
    if (!head.metadataSupported)
        return head.metadata == null &&
            head.metadataVersion == null &&
            after == null &&
            page.items.isEmpty() &&
            page.nextCursor == null
    val latest = head.metadataVersion ?: return false
    if (head.metadata == null || latest !in 0..DOCUMENT_METADATA_MAXIMUM_VERSION) return false
    if (page.items.size > DOCUMENT_METADATA_PAGE_SIZE || (after ?: -1) > latest) return false
    if (
        page.items.withIndex().any { (index, row) ->
            row.documentId != head.id ||
                row.version != (after ?: -1) + index + 1 ||
                row.version > latest ||
                row.documentVersion > head.version ||
                (row.version == latest && row.metadata != head.metadata)
        }
    )
        return false
    if (page.items.zipWithNext().any { (a, b) -> b.documentVersion <= a.documentVersion })
        return false
    val last = page.items.lastOrNull()?.version ?: after
    return if (page.nextCursor == null) last == latest
    else page.items.size == DOCUMENT_METADATA_PAGE_SIZE && page.nextCursor == last && last < latest
}
