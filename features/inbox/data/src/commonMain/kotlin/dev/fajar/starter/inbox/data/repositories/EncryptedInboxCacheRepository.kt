package dev.fajar.starter.inbox.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.inbox.data.dto.*
import dev.fajar.starter.inbox.data.mappers.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedInboxCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("inbox-content-key") private val credentials: CredentialStore,
) : InboxCacheRepository {
    private val keyCreation = Mutex()

    override fun observe(access: InboxAccess, query: InboxCacheQuery) =
        safeStorageFlow(
            when (query) {
                InboxCacheQuery.Checkpoint ->
                    replicas.observeCheckpoint(access.sessionId, inboxPartition(access)).map {
                        ReplicaPage(it, emptyList())
                    }
                is InboxCacheQuery.Page ->
                    replicas.observe(access.sessionId, inboxPartition(access), query.after, 31)
                is InboxCacheQuery.Item ->
                    replicas.observeDocument(access.sessionId, inboxPartition(access), query.id)
            },
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) InboxCachePage(null, emptyList(), null)
                else {
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Content key is unavailable." }
                        )
                    try {
                        val checkpointBytes =
                            cipher.decrypt(
                                key,
                                inboxAssociatedData(
                                    access,
                                    raw.handle.generation,
                                    "checkpoint",
                                    "",
                                    raw.revision,
                                ),
                                Base64.decode(raw.payload),
                            )
                        val checkpoint =
                            Json.decodeFromString<InboxCheckpointDto>(
                                    checkpointBytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toCheckpoint(raw, access)
                        val items =
                            page.documents.take(30).map { document ->
                                val bytes =
                                    cipher.decrypt(
                                        key,
                                        inboxAssociatedData(
                                            access,
                                            raw.handle.generation,
                                            "item",
                                            document.id,
                                            document.version,
                                        ),
                                        Base64.decode(requireNotNull(document.payload)),
                                    )
                                val item =
                                    Json.decodeFromString<InboxItemDto>(
                                            bytes.decodeToString(throwOnInvalidSequence = true)
                                        )
                                        .toItem(document.id)
                                require(
                                    item.version == document.version &&
                                        item.sortKey() == document.sortKey
                                )
                                item
                            }
                        InboxCachePage(
                            checkpoint,
                            items,
                            if (page.documents.size > 30) page.documents[29].sortKey else null,
                        )
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun checkpoint(access: InboxAccess) = safeStorageCall {
        val raw = replicas.checkpoint(access.sessionId, inboxPartition(access))
        if (raw == null) null
        else {
            val key =
                Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
            try {
                val bytes =
                    cipher.decrypt(
                        key,
                        inboxAssociatedData(
                            access,
                            raw.handle.generation,
                            "checkpoint",
                            "",
                            raw.revision,
                        ),
                        Base64.decode(raw.payload),
                    )
                Json.decodeFromString<InboxCheckpointDto>(
                        bytes.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toCheckpoint(raw, access)
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun begin(checkpoint: InboxCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            require(checkpoint.revision == 0L && !checkpoint.published)
            val access = checkpoint.access
            val key =
                keyCreation.withLock {
                    val saved = credentials.read()
                    if (saved != null) Base64.decode(saved)
                    else {
                        check(
                            replicas.checkpoint(access.sessionId, inboxPartition(access)) == null
                        ) {
                            "Existing cache requires its content key."
                        }
                        val generated = cipher.generateKey()
                        try {
                            withContext(NonCancellable) {
                                credentials.write(Base64.encode(generated))
                            }
                            generated.copyOf()
                        } finally {
                            generated.fill(0)
                        }
                    }
                }
            try {
                val sealed =
                    cipher.encrypt(
                        key,
                        inboxAssociatedData(access, checkpoint.generation, "checkpoint", "", 0),
                        Json.encodeToString(checkpoint.toDto()).encodeToByteArray(),
                    )
                replicas.begin(
                    access.sessionId,
                    inboxPartition(access),
                    checkpoint.generation,
                    Base64.encode(sealed),
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: InboxCheckpoint,
        next: InboxCheckpoint,
        entries: List<InboxReplicaEntry>,
    ) = safeStorageCall {
        require(
            next.generation == previous.generation &&
                next.revision == previous.revision + 1 &&
                next.access == previous.access
        )
        require(!previous.published || next.published)
        val access = previous.access
        val batch = entries.toList()
        require(batch.size <= 50 && batch.map { it.id }.distinct().size == batch.size)
        val key =
            Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
        try {
            val documents =
                batch.map { entry ->
                    val item = entry.item
                    if (item == null) ReplicaDocument(entry.id, entry.version, entry.id, null)
                    else {
                        require(entry.id == item.id && entry.version == item.version)
                        val bytes =
                            cipher.encrypt(
                                key,
                                inboxAssociatedData(
                                    access,
                                    previous.generation,
                                    "item",
                                    entry.id,
                                    entry.version,
                                ),
                                Json.encodeToString(item.toDto()).encodeToByteArray(),
                            )
                        ReplicaDocument(
                            entry.id,
                            entry.version,
                            item.sortKey(),
                            Base64.encode(bytes),
                        )
                    }
                }
            val sealed =
                cipher.encrypt(
                    key,
                    inboxAssociatedData(access, next.generation, "checkpoint", "", next.revision),
                    Json.encodeToString(next.toDto()).encodeToByteArray(),
                )
            replicas.commit(
                ReplicaHandle(access.sessionId, inboxPartition(access), previous.generation),
                previous.revision,
                Base64.encode(sealed),
                documents,
                next.published,
            )
        } finally {
            key.fill(0)
        }
    }

    override suspend fun clear(access: InboxAccess) = safeStorageCall {
        replicas.clear(access.sessionId, inboxPartition(access))
    }
}
