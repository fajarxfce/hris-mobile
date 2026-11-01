package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageRepository
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationMessageRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationMessageRepository {
    private val insertions = Mutex()

    override fun observe(
        access: CollaborationLocalAccess,
        conversationId: String?,
    ): Flow<AppResult<List<CollaborationMessageRecord>>> =
        safeStorageFlow(
                flow {
                    require(conversationId == null || validCollaborationMessageId(conversationId))
                    emitAll(commands.observeAll(messageScope(access), messagePartition(access)))
                },
                mapValue = { rows ->
                    require(rows.size <= 128)
                    if (rows.isEmpty()) emptyList()
                    else
                        withKey(access) { codec ->
                            require(rows.map { it.id }.distinct().size == rows.size)
                            require(rows.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                            rows
                                .map { codec.record(it) }
                                .filter {
                                    conversationId == null ||
                                        it.intent.conversationId == conversationId
                                }
                        }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun get(access: CollaborationLocalAccess, id: String) = safeStorageCall {
        require(validCollaborationMessageId(id))
        commands.get(messageScope(access), messagePartition(access), id)?.let { raw ->
            withKey(access) { it.record(raw) }
        }
    }

    override suspend fun insert(
        access: CollaborationLocalAccess,
        intent: CollaborationMessageIntent,
    ) = safeStorageCall {
        intent.toDto(access)
        val scope = messageScope(access)
        insertions.withLock {
            val partition = messagePartition(access)
            val previous = commands.get(scope, partition, intent.id)
            withKey(access, create = previous == null) { codec ->
                if (previous != null) {
                    require(codec.record(previous).intent == intent)
                    CollaborationMessageInsertion.STORED
                } else {
                    val request = codec.request(intent)
                    val state = codec.state(intent.id, 0, CollaborationMessageState.Queued.toDto())
                    currentCoroutineContext().ensureActive()
                    when (
                        commands.insert(
                            scope,
                            CommandRecord(
                                partition,
                                intent.id,
                                intent.id,
                                intent.orderKey(),
                                request,
                                state,
                            ),
                        )
                    ) {
                        CommandInsertResult.Stored -> CollaborationMessageInsertion.STORED
                        CommandInsertResult.AlreadyStored -> {
                            require(
                                codec
                                    .record(
                                        requireNotNull(commands.get(scope, partition, intent.id))
                                    )
                                    .intent == intent
                            )
                            CollaborationMessageInsertion.STORED
                        }
                        CommandInsertResult.ScopeChanged ->
                            CollaborationMessageInsertion.SCOPE_CHANGED
                        CommandInsertResult.CapacityExceeded ->
                            CollaborationMessageInsertion.CAPACITY_EXCEEDED
                        CommandInsertResult.ResourceOccupied ->
                            error("Message resource identity mismatch")
                    }
                }
            }
        }
    }

    override suspend fun update(
        access: CollaborationLocalAccess,
        expected: CollaborationMessageRecord,
        state: CollaborationMessageState,
    ) = safeStorageCall {
        expected.intent.toDto(access)
        val detached = state.toDto()
        val previousState = expected.state.toDto().toState()
        require(collaborationMessageTransition(previousState, detached.toState()))
        require(expected.revision in 0 until 9_007_199_254_740_991L)
        val scope = messageScope(access)
        val partition = messagePartition(access)
        val raw = commands.get(scope, partition, expected.intent.id)
        if (raw == null || raw.revision != expected.revision) false
        else
            withKey(access) { codec ->
                if (codec.record(raw) != expected.copy(state = previousState)) false
                else {
                    val sealed = codec.state(expected.intent.id, expected.revision + 1, detached)
                    currentCoroutineContext().ensureActive()
                    commands.update(scope, partition, expected.intent.id, expected.revision, sealed)
                }
            }
    }

    private suspend fun <T> withKey(
        access: CollaborationLocalAccess,
        create: Boolean = false,
        block: suspend (CollaborationMessageCodec) -> T,
    ): T {
        val existing = credentials.read()
        val encoded =
            if (existing != null) existing
            else {
                check(create && !commands.containsRecords()) {
                    "Message content key is unavailable."
                }
                val generated = cipher.generateKey()
                try {
                    credentials.writeIfAbsent(Base64.encode(generated))
                } finally {
                    generated.fill(0)
                }
            }
        val key = Base64.decode(encoded)
        return try {
            require(key.size == 32)
            block(CollaborationMessageCodec(access, cipher, key))
        } finally {
            key.fill(0)
        }
    }
}
