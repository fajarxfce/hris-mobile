package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
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
) :
    CollaborationMessageRepository,
    CollaborationMessageCompletionRepository,
    CollaborationMessageCancellationRepository {
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
        val (active, archived) = existing(messageScope(access), messagePartition(access), id)
        if (active == null && archived == null) null
        else withKey(access) { decode(it, active, archived) }
    }

    override suspend fun readCompletion(access: CollaborationLocalAccess, id: String) =
        safeStorageCall {
            require(validCollaborationMessageId(id))
            commands.archived(messageScope(access), messagePartition(access), id)?.let { raw ->
                withKey(access) { it.completion(raw) }
            }
        }

    override suspend fun archiveCompletion(
        access: CollaborationLocalAccess,
        completion: CollaborationMessageCompletion,
    ) = safeStorageCall {
        require(completion.eventIds.size in 1..128)
        val detached = completion.copy(eventIds = completion.eventIds.toList())
        detached.record.intent.toDto(access)
        require(collaborationCompletionReceipt(detached) != null)
        val scope = messageScope(access)
        val partition = messagePartition(access)
        val id = detached.record.intent.id
        val archived = commands.archived(scope, partition, id)
        if (archived != null) withKey(access) { it.completion(archived) == detached }
        else {
            val raw = commands.get(scope, partition, id)
            if (raw == null) false
            else
                withKey(access) { codec ->
                    if (codec.record(raw) != detached.record) false
                    else {
                        val proof = codec.completion(raw, detached.eventIds)
                        currentCoroutineContext().ensureActive()
                        commands.archive(scope, raw, proof)
                    }
                }
        }
    }

    override suspend fun cancel(
        access: CollaborationLocalAccess,
        expected: CollaborationMessageRecord,
    ) = safeStorageCall {
        expected.intent.toDto(access)
        require(validCollaborationCancellation(expected))
        val scope = messageScope(access)
        val partition = messagePartition(access)
        val archived = commands.archived(scope, partition, expected.intent.id)
        if (archived != null) matchingCancellation(access, expected, archived)
        else {
            val raw = commands.get(scope, partition, expected.intent.id)
            if (raw == null)
                matchingCancellation(
                    access,
                    expected,
                    commands.archived(scope, partition, expected.intent.id),
                )
            else
                withKey(access) { codec ->
                    if (codec.record(raw) != expected) false
                    else {
                        val proof = codec.cancellation(raw)
                        currentCoroutineContext().ensureActive()
                        if (commands.archive(scope, raw, proof)) true
                        else
                            commands.archived(scope, partition, expected.intent.id)?.let {
                                codec.archivedRecord(it) ==
                                    expected.copy(state = CollaborationMessageState.Cancelled)
                            } == true
                    }
                }
        }
    }

    private suspend fun matchingCancellation(
        access: CollaborationLocalAccess,
        expected: CollaborationMessageRecord,
        archived: CommandArchive?,
    ): Boolean =
        archived != null &&
            withKey(access) {
                it.archivedRecord(archived) ==
                    expected.copy(state = CollaborationMessageState.Cancelled)
            }

    override suspend fun insert(
        access: CollaborationLocalAccess,
        intent: CollaborationMessageIntent,
    ) = safeStorageCall {
        intent.toDto(access)
        val scope = messageScope(access)
        insertions.withLock {
            val partition = messagePartition(access)
            val (previous, completed) = existing(scope, partition, intent.id)
            withKey(access, create = previous == null && completed == null) { codec ->
                if (previous != null || completed != null) {
                    require(requireNotNull(decode(codec, previous, completed)).intent == intent)
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
                            val (active, archived) = existing(scope, partition, intent.id)
                            require(
                                requireNotNull(decode(codec, active, archived)).intent == intent
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

    private suspend fun existing(
        scope: CommandScope,
        partition: String,
        id: String,
    ): Pair<CommandRecord?, CommandArchive?> {
        val active = commands.get(scope, partition, id)
        return active to if (active == null) commands.archived(scope, partition, id) else null
    }

    private suspend fun decode(
        codec: CollaborationMessageCodec,
        active: CommandRecord?,
        archived: CommandArchive?,
    ): CollaborationMessageRecord? =
        if (active != null) codec.record(active) else archived?.let { codec.archivedRecord(it) }

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
