package dev.fajar.starter.collaboration.data.drafts

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.ReplicaScope

/** Exact encrypted namespaces. Company frames retain the existing byte protocol. */
internal sealed class CollaborationComposerCoordinates(
    private val audience: String,
    private val accountId: String,
    private val ownerId: String,
    private val contentNamespace: String,
    private val outboxNamespace: String,
) {
    abstract val scope: ReplicaScope
    abstract val partition: String

    // Scope validation is lazy so acquisition stays inside the repository's safe I/O boundary.
    class Company(private val access: CollaborationLocalAccess) :
        CollaborationComposerCoordinates(
            access.audience,
            access.accountId,
            access.companyId,
            "hris-composer-content-v2",
            "hris-composer-outbox-v1",
        ) {
        override val scope
            get() = historyScope(access)

        override val partition
            get() = draftPartition(access)
    }

    class Space(private val access: CollaborationSpaceLocalAccess) :
        CollaborationComposerCoordinates(
            access.audience,
            access.accountId,
            access.spaceId,
            "hris-space-composer-content-v1",
            "hris-space-composer-outbox-v1",
        ) {
        override val scope
            get() = spaceHistoryScope(access)

        override val partition: String
            get() {
                spaceHistoryScope(access)
                return COLLABORATION_HISTORY_PREFIX + "space-composer/v1/" + access.spaceId
            }
    }

    fun draftFrame(generation: String, kind: String, revision: Long, sort: String) =
        listOf(
            contentNamespace,
            audience,
            accountId,
            ownerId,
            partition,
            generation,
            kind,
            revision.toString(),
            sort,
        )

    fun outboxFrame(generation: String, id: String, revision: Long) =
        listOf(
            outboxNamespace,
            audience,
            accountId,
            ownerId,
            partition,
            generation,
            id,
            revision.toString(),
        )

    override fun toString() = "CollaborationComposerCoordinates([redacted])"
}
