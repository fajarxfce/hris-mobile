package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import kotlinx.coroutines.flow.first

/** Revisions and their visible pointer share the original history transaction and owner. */
internal class CollaborationHistoryEditBatch(private val batch: CollaborationHistoryBatch) {
    private val codec
        get() = batch.codec

    suspend fun apply(edit: CollaborationMessageEdit) {
        require(validCollaborationMessageEdit(edit))
        val existing = batch.alias(edit.eventId)
        require(existing == null || existing.sender == edit.sender)
        require(
            existing == null ||
                existing.replacement ||
                (existing.redacted && existing.messageId == null)
        )
        val target = requireNotNull(batch.alias(edit.targetEventId))
        require(!target.replacement)
        val id = requireNotNull(target.messageId)
        val original = resolveOriginal(id, requireNotNull(batch.message(id)))
        if (existing?.messageId != null) {
            require(existing.messageId == id && existing.timestamp == edit.timestamp)
            require(existing.replacementHash == edit.contentHash)
            require(existing.replacementTarget == edit.targetEventId)
        }
        val fact =
            CollaborationHistoryEventFact(
                target.eventId,
                target.sender,
                target.timestamp,
                target.redacted,
            )
        val eligible =
            existing?.redacted != true && acceptsCollaborationMessageEdit(original, fact, edit)
        retainEvidence(id, edit, eligible)
        // Retain body-free transport proof even when a redaction arrived before the replacement.
        if (existing?.redacted == true) return
        val revision = CollaborationMessageRevision(edit, requireNotNull(target.timestamp))
        val revisionId = codec.revisionId(edit.eventId)
        val retained =
            batch.revisions[revisionId]
                ?: batch.raw(revisionId)?.let { codec.revision(requireNotNull(batch.previous), it) }
        if (retained != null) {
            require(retained.messageId == id && retained.revision.toDomain() == revision)
            require(existing != null)
            return
        }
        if (!eligible) return
        require(existing == null && revisionId !in batch.removedRevisions)
        batch.revisions[revisionId] = HistoryRevisionDto(id, revision.toHistoryEditDto())
        batch.aliases[codec.aliasId(edit.eventId)] =
            HistoryAliasDto(
                edit.eventId,
                edit.sender,
                id,
                edit.timestamp,
                false,
                true,
                edit.contentHash,
                edit.targetEventId,
            )
        if (supersedesCollaborationMessageEdit(original.latestEdit?.edit, edit))
            batch.messages[id] = original.copy(latestEdit = revision)
    }

    private suspend fun retainEvidence(
        id: String,
        edit: CollaborationMessageEdit,
        eligible: Boolean,
    ) {
        val value =
            HistoryEditEvidenceDto(
                id,
                edit.eventId,
                edit.sender,
                edit.targetEventId,
                edit.timestamp,
                edit.contentHash,
                edit.operationId,
                codec.editInputFingerprint(edit),
                eligible,
            )
        val previous = batch.editEvidence(edit.eventId)
        if (previous == null) batch.editEvidence[codec.editEvidenceId(edit.eventId)] = value
        else require(value.copy(initiallyEligible = previous.initiallyEligible) == previous)
    }

    suspend fun redact(
        change: CollaborationHistoryChange.Redacted,
        aliasId: String,
        alias: HistoryAliasDto,
    ) {
        require(alias.replacement && change.sender == alias.sender)
        if (alias.redacted) return
        val id = requireNotNull(alias.messageId)
        val original = requireNotNull(batch.message(id))
        val revisionId = codec.revisionId(change.eventId)
        val raw = requireNotNull(batch.raw(revisionId))
        val revision = codec.revision(requireNotNull(batch.previous), raw)
        require(revision.messageId == id && revision.revision.eventId == change.eventId)
        batch.removedRevisions[revisionId] = raw.sortKey
        batch.revisions.remove(revisionId)
        batch.aliases[aliasId] = alias.copy(redacted = true)
        if (original.latestEdit?.edit?.eventId == change.eventId)
            batch.messages[id] = original.copy(latestEdit = latest(id, original))
    }

    /** Legacy histories may already contain earlier aliases than their stable primary timestamp. */
    suspend fun resolveOriginal(
        id: String,
        original: CollaborationHistoryMessage,
    ): CollaborationHistoryMessage {
        if (original.firstAcceptedAt != null) return original
        var first = original.timestamp
        for (eventId in original.eventIds) {
            val alias = requireNotNull(batch.alias(eventId))
            require(!alias.replacement && alias.messageId == id && alias.sender == original.sender)
            first = minOf(first, requireNotNull(alias.timestamp))
        }
        val indexed = original.copy(firstAcceptedAt = first, latestEdit = null)
        val current = original.latestEdit
        val revised =
            indexed.copy(
                latestEdit =
                    when {
                        current == null -> null
                        admissible(indexed, current) -> current
                        else -> latest(id, indexed)
                    }
            )
        batch.messages[id] = revised
        return revised
    }

    suspend fun latest(
        id: String,
        original: CollaborationHistoryMessage,
    ): CollaborationMessageRevision? {
        if (original.redacted) return null
        var best: CollaborationMessageRevision? = null
        for (value in batch.revisions.values.filter { it.messageId == id }) {
            val candidate = value.revision.toDomain()
            if (!admissible(original, candidate)) continue
            if (supersedesCollaborationMessageEdit(best?.edit, candidate.edit)) best = candidate
        }
        val ceiling =
            minOf(
                9_007_199_254_740_991L,
                (original.firstAcceptedAt ?: original.timestamp) +
                    CollaborationMessageEditWindowMillis - 1,
            )
        var after: String? = (9_007_199_254_740_991L - ceiling).toString().padStart(16, '0') + "/"
        // Bounded recovery, including a tied server timestamp split across index pages. The
        // opaque index deliberately contains neither message text nor provider event identifiers.
        repeat(16) {
            val page =
                batch.replicas
                    .observeQueries(
                        batch.scope,
                        batch.partition,
                        listOf(ReplicaQuery(codec.revisionTag(id), after = after, limit = 32)),
                    )
                    .first()
            if (page.checkpoint != batch.previous) throw CollaborationHistoryPreparationChanged()
            require(page.pages.size == 1)
            val documents = page.pages.single()
            var oldest: Long? = null
            for (raw in documents) {
                val retained = codec.revision(requireNotNull(batch.previous), raw)
                require(retained.messageId == id)
                val candidate = retained.revision.toDomain()
                oldest = candidate.edit.timestamp
                if (raw.id in batch.removedRevisions) continue
                if (!admissible(original, candidate)) continue
                if (supersedesCollaborationMessageEdit(best?.edit, candidate.edit)) best = candidate
            }
            if (
                documents.size < 32 ||
                    best?.let { requireNotNull(oldest) < it.edit.timestamp } == true
            )
                return best
            after = documents.last().sortKey
        }
        error("Replacement recovery exceeds the bounded history window.")
    }

    private fun admissible(
        original: CollaborationHistoryMessage,
        candidate: CollaborationMessageRevision,
    ) =
        acceptsCollaborationMessageEdit(
            original,
            CollaborationHistoryEventFact(
                candidate.edit.targetEventId,
                candidate.edit.sender,
                candidate.targetAcceptedAt,
                false,
            ),
            candidate.edit,
        )
}
