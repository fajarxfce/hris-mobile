package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.domain.policies.COLLABORATION_SPACE_RECIPIENT_CAPACITY
import dev.fajar.starter.database.*

/** Detaches bounded raw projection frames before any key or cipher suspension. */
internal object CollaborationSpaceRecipientProjectionCodec {
    fun header(raw: ReplicaQueryPage, peer: String): ReplicaPage {
        require(raw.pages.size == 2)
        val expected = listOf(peer, RECIPIENT_OUTBOX_VERSION_ID)
        val captured =
            raw.pages
                .mapIndexed { index, page ->
                    require(page.size <= 1)
                    page
                        .take(2)
                        .map { document ->
                            require(document.id == expected[index] && document.tags.isEmpty())
                            require(document.payload != null)
                            document.copy(tags = emptySet())
                        }
                        .also { require(it.size <= 1) }
                }
                .flatten()
        require(raw.checkpoint != null || captured.isEmpty())
        return ReplicaPage(raw.checkpoint, captured)
    }

    fun originals(raw: ReplicaQueryPage): ReplicaPage {
        require(raw.pages.size == 1)
        val page = raw.pages.single()
        require(page.size <= COLLABORATION_SPACE_RECIPIENT_CAPACITY + 1)
        val captured =
            page.take(COLLABORATION_SPACE_RECIPIENT_CAPACITY + 2).map { document ->
                require(document.tags == setOf(RECIPIENT_OUTBOX_TAG) && document.payload != null)
                document.copy(tags = setOf(RECIPIENT_OUTBOX_TAG))
            }
        require(
            CollaborationSpaceRecipientSnapshotCodec.inventory(captured) <=
                COLLABORATION_SPACE_RECIPIENT_CAPACITY
        )
        require(captured.zipWithNext().all { (a, b) -> a.id < b.id })
        require(raw.checkpoint != null || captured.isEmpty())
        return ReplicaPage(raw.checkpoint, captured)
    }
}
