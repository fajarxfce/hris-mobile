package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationDraft
import dev.fajar.starter.collaboration.domain.repositories.CollaborationDraftRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationDraftRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationDraftRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observe(access: CollaborationLocalAccess, conversationId: String) =
        safeStorageFlow(
                flow {
                    emitAll(
                        replicas.observeDocument(
                            historyScope(access),
                            draftPartition(access, conversationId),
                            "draft",
                        )
                    )
                },
                mapValue = { decode(access, conversationId, it) },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun get(access: CollaborationLocalAccess, conversationId: String) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                val scope = historyScope(access)
                val partition = draftPartition(access, conversationId)
                val page = replicas.observeDocument(scope, partition, "draft").first()
                val result = decode(access, conversationId, page)
                require(replicas.checkpoint(scope, partition) == page.checkpoint)
                result
            }
        }

    override suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validCollaborationDraft(replacement))
                require(
                    expected == null ||
                        (validCollaborationDraft(expected) &&
                            expected.conversationId == replacement.conversationId)
                )
                val scope = historyScope(access)
                val partition = draftPartition(access, replacement.conversationId)
                val page = replicas.observeDocument(scope, partition, "draft").first()
                val current = decode(access, replacement.conversationId, page)
                if (replicas.checkpoint(scope, partition) != page.checkpoint)
                    return@safeStorageCall false
                if (current == replacement) return@safeStorageCall true
                if (current != expected) return@safeStorageCall false
                require(current?.revision != replacement.revision)
                val snapshot =
                    keys.use(create = page.checkpoint == null) { content, _ ->
                        CollaborationDraftCodec(access, replacement.conversationId, cipher, content)
                            .encode(replacement)
                    }
                currentCoroutineContext().ensureActive()
                replicas.replace(scope, partition, page.checkpoint, snapshot)
            }
        }

    private suspend fun decode(
        access: CollaborationLocalAccess,
        conversationId: String,
        page: ReplicaPage,
    ): CollaborationDraft? {
        if (page.checkpoint == null) {
            require(page.documents.isEmpty())
            return null
        }
        return keys.use { content, _ ->
            CollaborationDraftCodec(access, conversationId, cipher, content).decode(page)
        }
    }
}
