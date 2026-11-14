package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceInboxEntry
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlin.time.Instant

internal class InboxWindowStorageFixture(owner: CollaborationHistoryDatabaseOwner) {
    val base = CollaborationHistoryFixture(owner)
    val access =
        CollaborationSpaceLocalAccess(
            base.access.sessionId,
            base.access.audience,
            base.access.accountId,
            historyId(9000),
        )
    private var draftRevision = 10_000

    fun writer() =
        EncryptedCollaborationSpaceInboxRepository(base.database.replicas, base.cipher, base.keys)

    fun reader(store: ReplicaStore = base.database.replicas, cipher: ContentCipher = base.cipher) =
        EncryptedCollaborationSpaceInboxWindowRepository(store, cipher, base.keys)

    suspend fun read(
        anchor: CollaborationSpaceInboxWindowAnchor = CollaborationSpaceInboxWindowAnchor.Latest,
        limit: Int = 50,
        store: ReplicaStore = base.database.replicas,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxWindow>>(
                reader(store).read(access, anchor, limit)
            )
            .value

    suspend fun seed(count: Int) {
        for (id in 1..count) seedRow(id, 10_000L - id)
    }

    suspend fun seedRow(id: Int, createdAt: Long) {
        val index = writer()
        val current =
            assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                    index.conversation(access, historyId(id))
                )
                .value
        assertEquals(
            AppResult.Success(true),
            index.publish(
                access,
                CollaborationSpaceInboxPublication(
                    current.version,
                    current.version?.lastOrdinal ?: 0L,
                    collaborationSpaceInboxEntry(historyId(id), createdAt, null, null),
                ),
            ),
        )
    }

    suspend fun move(id: Int, timestamp: Long) {
        val index = writer()
        val current =
            assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                    index.conversation(access, historyId(id))
                )
                .value
        val entry = assertNotNull(current.entry)
        val ordinal = (current.version?.lastOrdinal ?: 0L) + 1
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    historyId(id),
                    historyId(++draftRevision),
                    "Moved local draft",
                    Instant.fromEpochMilliseconds(timestamp),
                ),
                CollaborationSpaceInboxPosition(timestamp, ordinal),
            )
        assertEquals(
            AppResult.Success(true),
            index.publish(
                access,
                CollaborationSpaceInboxPublication(
                    current.version,
                    ordinal,
                    collaborationSpaceInboxEntry(
                        entry.conversationId,
                        entry.createdAt,
                        draft,
                        entry.newest,
                    ),
                    draft = draft,
                ),
            ),
        )
    }
}
