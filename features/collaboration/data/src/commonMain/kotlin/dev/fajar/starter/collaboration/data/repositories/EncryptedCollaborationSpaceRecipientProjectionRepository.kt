package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecipientProjectionRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceRecipientProjectionRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceRecipientProjectionRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observe(access: CollaborationSpaceLocalAccess, peerAccountId: String) =
        flow {
                val reader = Observation(access, peerAccountId)
                try {
                    emitAll(
                        safeStorageFlow(
                                flow {
                                    require(
                                        validCollaborationSpaceRecipientCoordinates(
                                            access,
                                            peerAccountId,
                                        )
                                    )
                                    emitAll(
                                        replicas.observeQueries(
                                            spaceHistoryScope(access),
                                            recipientDraftPartition(access),
                                            listOf(
                                                ReplicaQuery(id = peerAccountId, limit = 1),
                                                ReplicaQuery(
                                                    id = RECIPIENT_OUTBOX_VERSION_ID,
                                                    limit = 1,
                                                ),
                                            ),
                                        )
                                    )
                                },
                                mapValue = reader::read,
                            )
                            .transform { result ->
                                when (result) {
                                    is AppResult.Failed -> {
                                        reader.clear()
                                        emit(result)
                                    }
                                    is AppResult.Success ->
                                        result.value?.let { emit(AppResult.Success(it)) }
                                }
                            }
                    )
                } finally {
                    reader.clear()
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** One collector owns decoded immutable source facts; typing does not reload originals. */
    private inner class Observation(val access: CollaborationSpaceLocalAccess, val peer: String) {
        private var version: CollaborationSpaceRecipientOutboxVersion? = null
        private var retained: List<CollaborationSpaceRecipientSubmission> = emptyList()

        fun clear() {
            version = null
            retained = emptyList()
        }

        suspend fun read(raw: ReplicaQueryPage): CollaborationSpaceRecipientProjection? {
            val header = CollaborationSpaceRecipientProjectionCodec.header(raw, peer)
            val checkpoint = header.checkpoint
            if (checkpoint == null) {
                clear()
                return CollaborationSpaceRecipientProjection(null, emptyList())
            }
            return keys.use { key, _ ->
                val source =
                    CollaborationSpaceRecipientOutboxVersionCodec(access, cipher, key)
                        .decode(
                            checkpoint,
                            header.documents.singleOrNull { it.id == RECIPIENT_OUTBOX_VERSION_ID },
                        )
                val draft =
                    header.documents
                        .singleOrNull { it.id == peer }
                        ?.let {
                            CollaborationSpaceRecipientDraftCodec(access, cipher, key)
                                .decode(checkpoint, it)
                        }
                if (version != source) {
                    val inventory =
                        replicas
                            .observeQueries(
                                spaceHistoryScope(access),
                                recipientDraftPartition(access),
                                listOf(
                                    ReplicaQuery(
                                        RECIPIENT_OUTBOX_TAG,
                                        limit = COLLABORATION_SPACE_RECIPIENT_CAPACITY + 1,
                                    )
                                ),
                            )
                            .map(CollaborationSpaceRecipientProjectionCodec::originals)
                            .first()
                    // A newer source will produce another header on this same owned subscription.
                    if (inventory.checkpoint != checkpoint) return@use null
                    require(source.revision != null || inventory.documents.isEmpty())
                    val codec = CollaborationSpaceRecipientSubmissionCodec(access, cipher, key)
                    val selected =
                        buildList {
                                for (document in inventory.documents) {
                                    currentCoroutineContext().ensureActive()
                                    val original = codec.decode(checkpoint, document)
                                    if (original.original.peerAccountId == peer) add(original)
                                }
                            }
                            .sortedWith(compareBy({ it.submittedAt }, { it.id }))
                    currentCoroutineContext().ensureActive()
                    retained = selected
                    version = source
                }
                CollaborationSpaceRecipientProjection(draft, retained.toList())
            }
        }
    }
}
