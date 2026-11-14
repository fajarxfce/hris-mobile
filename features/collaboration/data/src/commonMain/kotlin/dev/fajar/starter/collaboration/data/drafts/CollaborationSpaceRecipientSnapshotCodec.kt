package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.domain.policies.COLLABORATION_SPACE_RECIPIENT_CAPACITY
import dev.fajar.starter.database.*

/** Captures bounded raw recipient frames before any key/cipher suspension. No storage calls. */
internal object CollaborationSpaceRecipientSnapshotCodec {
    fun working(raw: ReplicaPage, expected: Set<String>): ReplicaPage {
        require(expected.size in 1..3 && raw.documents.size <= expected.size)
        val documents =
            raw.documents.take(expected.size + 1).map { document ->
                require(document.id in expected)
                val tags = document.tags
                require(
                    tags.isEmpty() ||
                        (document.id.startsWith("submission:") &&
                            tags == setOf(RECIPIENT_OUTBOX_TAG))
                )
                document.copy(
                    tags = if (tags.isEmpty()) emptySet() else setOf(RECIPIENT_OUTBOX_TAG)
                )
            }
        require(
            documents.size <= expected.size &&
                documents.map { it.id }.distinct().size == documents.size
        )
        require(raw.checkpoint != null || documents.isEmpty())
        val visible =
            documents.filter { document ->
                if (document.payload == null) {
                    val checkpoint = requireNotNull(raw.checkpoint)
                    require(document.id != RECIPIENT_OUTBOX_VERSION_ID)
                    require(
                        document.version in 0..checkpoint.revision &&
                            document.sortKey == document.id &&
                            document.tags.isEmpty()
                    )
                    false
                } else true
            }
        return raw.copy(documents = visible)
    }

    fun inventory(documents: List<ReplicaDocument>): Int {
        require(documents.size <= COLLABORATION_SPACE_RECIPIENT_CAPACITY + 1)
        val captured = documents.take(COLLABORATION_SPACE_RECIPIENT_CAPACITY + 2)
        require(captured.size <= COLLABORATION_SPACE_RECIPIENT_CAPACITY + 1)
        require(captured.map { it.id }.distinct().size == captured.size)
        require(
            captured.all {
                it.tags == setOf(RECIPIENT_OUTBOX_TAG) &&
                    it.id == recipientSubmissionId(it.id.removePrefix("submission:")) &&
                    it.sortKey == it.id
            }
        )
        return captured.size
    }
}
