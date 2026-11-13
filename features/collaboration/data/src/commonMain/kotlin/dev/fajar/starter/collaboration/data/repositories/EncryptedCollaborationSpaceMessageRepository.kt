@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.identity.CollaborationSpaceCommandKeys
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceMessageRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") credentials: AtomicCredentialStore,
    private val replicas: ReplicaStore,
    @Named("collaboration-history-content-key") historyCredentials: AtomicCredentialStore,
) :
    CollaborationSpaceMessageRepository,
    CollaborationSpaceMessageCompletionRepository,
    CollaborationSpaceMessageCancellationRepository,
    CollaborationSpaceEditPublicationRepository {
    private val keys = CollaborationSpaceCommandKeys(commands, cipher, credentials)
    private val historyKeys = CollaborationHistoryKeys(replicas, cipher, historyCredentials)
    private val insertions = Mutex()

    override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
        safeStorageFlow(
                flow {
                    require(
                        conversationId == null ||
                            validCollaborationSpaceConversationId(conversationId)
                    )
                    emitAll(
                        commands.observeAll(
                            spaceMessageScope(access),
                            spaceMessagePartition(access),
                        )
                    )
                },
                mapValue = { rows ->
                    require(rows.size <= 128 && rows.map { it.id }.distinct().size == rows.size)
                    require(rows.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                    if (rows.isEmpty()) emptyList()
                    else
                        keys.use { key ->
                            val codec = CollaborationSpaceMessageCodec(access, cipher, key)
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

    override fun observeRecord(access: CollaborationSpaceLocalAccess, id: String) =
        safeStorageFlow(
                flow {
                    require(validCollaborationSpaceConversationId(id))
                    emitAll(
                        commands.observeRecord(
                            spaceMessageScope(access),
                            spaceMessagePartition(access),
                            id,
                        )
                    )
                },
                mapValue = { raw -> decodeLookup(access, id, raw) },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun get(access: CollaborationSpaceLocalAccess, id: String) = safeStorageCall {
        decodeLookup(access, id, lookup(access, id))
    }

    override suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        intent: CollaborationSpaceMessageIntent,
    ): AppResult<CollaborationMessageInsertion> {
        val result = safeStorageCall {
            require(validCollaborationSpaceMessageIntent(access, intent))
            require(intent.inboxOrigin == null)
            val scope = spaceMessageScope(access)
            insertions.withLock {
                val previous = lookup(access, intent.id)
                keys.use(create = previous.active == null && previous.archived == null) { key ->
                    val codec = CollaborationSpaceMessageCodec(access, cipher, key)
                    val retained =
                        previous.active?.let { codec.record(it) }
                            ?: previous.archived?.let { codec.archivedRecord(it) }
                    if (retained != null) {
                        require(retained.intent == intent)
                        return@use CollaborationMessageInsertion.STORED
                    }
                    val raw =
                        CommandRecord(
                            spaceMessagePartition(access),
                            intent.id,
                            intent.id,
                            intent.orderKey(),
                            codec.request(intent),
                            codec.state(intent.id, 0, CollaborationMessageState.Queued.toDto()),
                        )
                    val wake =
                        WorkWakeRequest(
                            CollaborationSpaceRecoveryWake.WORK_KEY,
                            Uuid.random().toString(),
                            SPACE_RECOVERY_WAKE_MAX_KEYS,
                        )
                    currentCoroutineContext().ensureActive()
                    when (commands.insertBatchAndWake(scope, listOf(raw), wake)) {
                        CommandInsertResult.Stored -> CollaborationMessageInsertion.STORED
                        CommandInsertResult.AlreadyStored -> {
                            val exact = lookup(access, intent.id)
                            val saved =
                                exact.active?.let { codec.record(it) }
                                    ?: exact.archived?.let { codec.archivedRecord(it) }
                            if (saved == null) CollaborationMessageInsertion.SCOPE_CHANGED
                            else {
                                require(saved.intent == intent)
                                CollaborationMessageInsertion.STORED
                            }
                        }
                        CommandInsertResult.ScopeChanged ->
                            CollaborationMessageInsertion.SCOPE_CHANGED
                        CommandInsertResult.CapacityExceeded ->
                            CollaborationMessageInsertion.CAPACITY_EXCEEDED
                        CommandInsertResult.ResourceOccupied ->
                            error("Message resource identity mismatch")
                        null -> null
                    }
                }
            }
        }
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                result.value?.let { AppResult.Success(it) }
                    ?: AppResult.Failed(
                        Failure(
                            FailureKind.Conflict,
                            "Could not retain chat recovery.",
                            code = "client.collaboration_space_recovery_capacity",
                        )
                    )
        }
    }

    override suspend fun readEditSource(
        access: CollaborationSpaceLocalAccess,
        source: CollaborationSpaceHistoryLocation,
        eventId: String,
    ): AppResult<CollaborationSpaceEditSourceSnapshot?> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validCollaborationEventId(eventId))
                validateHistoryRoom(source.roomId)
                val scope = spaceHistoryScope(access)
                val partition =
                    when (source) {
                        is CollaborationSpaceHistoryLocation.Space -> {
                            require(source.access == access)
                            spaceHistoryPartition(access, source.conversationId)
                        }
                        is CollaborationSpaceHistoryLocation.Original -> {
                            require(historyScope(source.access) == scope)
                            historyPartition(source.access, source.conversationId)
                        }
                    }
                val raw = replicas.checkpoint(scope, partition)
                if (raw == null) {
                    currentCoroutineContext().ensureActive()
                    return@safeStorageCall if (replicas.checkpoint(scope, partition) == null)
                        CollaborationSpaceEditSourceSnapshot(null, null, null)
                    else null
                }
                if (!raw.published) return@safeStorageCall null
                try {
                    historyKeys.use { content, index ->
                        val codec =
                            when (source) {
                                is CollaborationSpaceHistoryLocation.Space ->
                                    CollaborationHistoryCodec(
                                        source.access,
                                        source.conversationId,
                                        source.roomId,
                                        cipher,
                                        content,
                                        index,
                                    )
                                is CollaborationSpaceHistoryLocation.Original ->
                                    CollaborationHistoryCodec(
                                        source.access,
                                        source.conversationId,
                                        source.roomId,
                                        cipher,
                                        content,
                                        index,
                                    )
                            }
                        codec.checkpoint(raw)
                        val candidate =
                            CollaborationEditTargetReader(replicas, raw, codec, source.roomId)
                                .get(eventId)
                        val original =
                            CollaborationHistorySnapshotReader(replicas, raw, codec)
                                .read(listOf(eventId))
                                .single()
                                .original
                        currentCoroutineContext().ensureActive()
                        if (replicas.checkpoint(scope, partition) == raw)
                            CollaborationSpaceEditSourceSnapshot(
                                raw.handle.generation + ":" + raw.revision,
                                original,
                                candidate,
                            )
                        else null
                    }
                } catch (_: CollaborationHistoryPreparationChanged) {
                    currentCoroutineContext().ensureActive()
                    null
                }
            }
        }

    override suspend fun publishEdit(
        access: CollaborationSpaceLocalAccess,
        intent: CollaborationSpaceMessageIntent,
        source: CollaborationSpaceHistoryLocation,
        sourceRevision: String,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<CollaborationSpaceEditPublicationResult> {
        val captured = publication.copy(activities = publication.activities.toList())
        return withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validCollaborationSpaceEditPublication(access, intent, captured))
                require(sourceRevision.length in 1..128)
                require(source.roomId == requireNotNull(intent.editTarget).roomId)
                val scope = spaceHistoryScope(access)
                val sourcePartition =
                    when (source) {
                        is CollaborationSpaceHistoryLocation.Space -> {
                            require(
                                source.access == access &&
                                    source.conversationId == intent.conversationId
                            )
                            spaceHistoryPartition(access, source.conversationId)
                        }
                        is CollaborationSpaceHistoryLocation.Original -> {
                            require(historyScope(source.access) == scope)
                            historyPartition(source.access, source.conversationId)
                        }
                    }
                val historyOrigin =
                    intent.inboxOrigin?.activity as? CollaborationSpaceInboxSource.History
                if (historyOrigin != null) {
                    require(
                        historyOrigin.companyId ==
                            (source as? CollaborationSpaceHistoryLocation.Original)
                                ?.access
                                ?.companyId
                    )
                    require(
                        historyOrigin.conversationId == source.conversationId &&
                            historyOrigin.roomId == source.roomId
                    )
                }
                insertions.withLock {
                    val retained = decodeLookup(access, intent.id, lookup(access, intent.id))
                    if (retained != null) {
                        require(retained.intent == intent && retained.intent.inboxOrigin != null)
                        return@withLock CollaborationSpaceEditPublicationResult.RETAINED
                    }
                    val mutations =
                        encryptEditProjection(
                            access,
                            source,
                            sourcePartition,
                            sourceRevision,
                            captured,
                        ) ?: return@withLock CollaborationSpaceEditPublicationResult.CHANGED
                    keys.use(create = true) { commandKey ->
                        val commandCodec =
                            CollaborationSpaceMessageCodec(access, cipher, commandKey)
                        val raw =
                            CommandRecord(
                                spaceMessagePartition(access),
                                intent.id,
                                intent.id,
                                intent.orderKey(),
                                commandCodec.request(intent),
                                commandCodec.state(
                                    intent.id,
                                    0,
                                    CollaborationMessageState.Queued.toDto(),
                                ),
                            )
                        val wake =
                            WorkWakeRequest(
                                CollaborationSpaceRecoveryWake.WORK_KEY,
                                Uuid.random().toString(),
                                SPACE_RECOVERY_WAKE_MAX_KEYS,
                            )
                        currentCoroutineContext().ensureActive()
                        when (
                            commands.insertAndMutate(
                                spaceMessageScope(access),
                                raw,
                                mutations,
                                wake,
                            )
                        ) {
                            CommandInsertResult.Stored ->
                                CollaborationSpaceEditPublicationResult.STORED
                            // The raw replay compares the complete immutable request, including
                            // origin.
                            CommandInsertResult.AlreadyStored ->
                                CollaborationSpaceEditPublicationResult.RETAINED
                            CommandInsertResult.ScopeChanged ->
                                CollaborationSpaceEditPublicationResult.SCOPE_CHANGED
                            CommandInsertResult.CapacityExceeded ->
                                CollaborationSpaceEditPublicationResult.CAPACITY_EXCEEDED
                            CommandInsertResult.ResourceOccupied ->
                                error("Edit resource identity mismatch")
                            null -> CollaborationSpaceEditPublicationResult.CHANGED
                        }
                    }
                }
            }
        }
    }

    /** Acquire and encrypt one checked projection without touching the command ledger. */
    private suspend fun encryptEditProjection(
        access: CollaborationSpaceLocalAccess,
        source: CollaborationSpaceHistoryLocation,
        sourcePartition: String,
        sourceRevision: String,
        captured: CollaborationSpaceInboxPublication,
    ): List<ReplicaMutation>? {
        val scope = spaceHistoryScope(access)
        val conversationId = captured.entry.conversationId
        val rawSource = replicas.checkpoint(scope, sourcePartition) ?: return null
        if (
            !rawSource.published ||
                rawSource.handle.generation + ":" + rawSource.revision != sourceRevision
        )
            return null
        return historyKeys.use { content, index ->
            val historyCodec =
                when (source) {
                    is CollaborationSpaceHistoryLocation.Space ->
                        CollaborationHistoryCodec(
                            source.access,
                            source.conversationId,
                            source.roomId,
                            cipher,
                            content,
                            index,
                        )
                    is CollaborationSpaceHistoryLocation.Original ->
                        CollaborationHistoryCodec(
                            source.access,
                            source.conversationId,
                            source.roomId,
                            cipher,
                            content,
                            index,
                        )
                }
            historyCodec.checkpoint(rawSource)
            val codec = CollaborationSpaceInboxCodec(access, cipher, content, index)
            val partition = spaceInboxPartition(access)
            val current = replicas.checkpoint(scope, partition)
            if (
                current?.published == false ||
                    current?.let { codec.version(it) } != captured.expected
            )
                return@use null
            val ids =
                buildList {
                        add(codec.id("entry", conversationId))
                        add(codec.id("draft", conversationId))
                        captured.entry.newest?.let {
                            add(codec.id("activity", conversationId, it.source))
                        }
                        captured.activities.forEach {
                            add(codec.id("activity", conversationId, it.source))
                        }
                    }
                    .distinct()
            val documents = replicas.workingDocuments(scope, partition, ids)
            if (documents.checkpoint != current) return@use null
            val mutation = codec.mutation(current, documents.documents, captured) ?: return@use null
            listOf(ReplicaMutation.Check(sourcePartition, rawSource), mutation)
        }
    }

    override suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceMessageRecord,
        state: CollaborationMessageState,
    ) = safeStorageCall {
        require(validCollaborationSpaceMessageRecord(access, expected))
        val previous = expected.state.toDto().toState()
        val next = state.toDto().toState()
        require(collaborationMessageTransition(previous, next))
        require(expected.revision in 0 until 9_007_199_254_740_991L)
        require(
            validCollaborationSpaceMessageRecord(
                access,
                expected.copy(revision = expected.revision + 1, state = next),
            )
        )
        val raw = lookup(access, expected.intent.id).active
        if (raw == null || raw.revision != expected.revision) false
        else
            keys.use { key ->
                val codec = CollaborationSpaceMessageCodec(access, cipher, key)
                if (codec.record(raw) != expected.copy(state = previous)) false
                else {
                    val sealed =
                        codec.state(expected.intent.id, expected.revision + 1, next.toDto())
                    currentCoroutineContext().ensureActive()
                    commands.update(
                        spaceMessageScope(access),
                        spaceMessagePartition(access),
                        expected.intent.id,
                        expected.revision,
                        sealed,
                    )
                }
            }
    }

    override suspend fun readCompletion(access: CollaborationSpaceLocalAccess, id: String) =
        safeStorageCall {
            lookup(access, id).archived?.let { raw ->
                keys.use { key ->
                    CollaborationSpaceMessageCodec(access, cipher, key).completion(raw)
                }
            }
        }

    override suspend fun archiveCompletion(
        access: CollaborationSpaceLocalAccess,
        completion: CollaborationSpaceMessageCompletion,
        repair: CollaborationSpaceInboxRepairPublication,
    ) = safeStorageCall {
        require(validCollaborationSpaceInboxRepairPublication(repair))
        require(completion.eventIds.size in 1..128)
        val detached = completion.copy(eventIds = completion.eventIds.toList())
        require(validCollaborationSpaceMessageCompletion(access, detached))
        val raw = lookup(access, detached.record.intent.id)
        if (raw.active == null && raw.archived == null) false
        else
            keys.use { key ->
                val codec = CollaborationSpaceMessageCodec(access, cipher, key)
                val archived = raw.archived
                if (archived != null) codec.completion(archived) == detached
                else {
                    val active = requireNotNull(raw.active)
                    if (codec.record(active) != detached.record) false
                    else {
                        val proof = codec.completion(active, detached.eventIds)
                        currentCoroutineContext().ensureActive()
                        archiveWithRepairDemand(access, active, proof, repair)
                    }
                }
            }
    }

    override suspend fun cancel(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceMessageRecord,
        repair: CollaborationSpaceInboxRepairPublication,
    ) = safeStorageCall {
        require(validCollaborationSpaceInboxRepairPublication(repair))
        require(validCollaborationSpaceMessageCancellation(access, expected))
        val raw = lookup(access, expected.intent.id)
        if (raw.active == null && raw.archived == null) false
        else
            keys.use { key ->
                val codec = CollaborationSpaceMessageCodec(access, cipher, key)
                val archived = raw.archived
                if (archived != null)
                    codec.archivedRecord(archived) ==
                        expected.copy(state = CollaborationMessageState.Cancelled)
                else {
                    val active = requireNotNull(raw.active)
                    if (codec.record(active) != expected) false
                    else {
                        val proof = codec.cancellation(active)
                        currentCoroutineContext().ensureActive()
                        archiveWithRepairDemand(access, active, proof, repair)
                    }
                }
            }
    }

    private suspend fun archiveWithRepairDemand(
        access: CollaborationSpaceLocalAccess,
        active: CommandRecord,
        proof: String,
        repair: CollaborationSpaceInboxRepairPublication,
    ): Boolean {
        val scope = spaceHistoryScope(access)
        val partition = spaceInboxRepairProgressPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && repair.expected != null) return false
        return historyKeys.use(create = raw == null && repair.expected == null) { content, _ ->
            val codec = CollaborationSpaceInboxRepairProgressCodec(access, cipher, content)
            val retained = raw?.let { codec.decode(it) }
            if (retained != repair.expected) return@use false
            val snapshot =
                ReplicaSnapshot(
                    repair.replacement.generation,
                    codec.encode(repair.replacement),
                    emptyList(),
                )
            currentCoroutineContext().ensureActive()
            commands.archiveAndMutate(
                spaceMessageScope(access),
                active,
                proof,
                listOf(ReplicaMutation.Replace(partition, raw, snapshot)),
            )
        }
    }

    private suspend fun lookup(access: CollaborationSpaceLocalAccess, id: String): CommandLookup {
        require(validCollaborationSpaceConversationId(id))
        return commands
            .observeRecord(spaceMessageScope(access), spaceMessagePartition(access), id)
            .first()
            .also {
                require(it.active == null || it.archived == null)
                require(it.active?.id?.let { actual -> actual == id } != false)
                require(it.archived?.record?.id?.let { actual -> actual == id } != false)
            }
    }

    private suspend fun decodeLookup(
        access: CollaborationSpaceLocalAccess,
        id: String,
        raw: CommandLookup,
    ): CollaborationSpaceMessageRecord? {
        require(raw.active == null || raw.archived == null)
        require(raw.active?.id?.let { it == id } != false)
        require(raw.archived?.record?.id?.let { it == id } != false)
        if (raw.active == null && raw.archived == null) return null
        return keys.use { key ->
            val codec = CollaborationSpaceMessageCodec(access, cipher, key)
            raw.active?.let { codec.record(it) } ?: raw.archived?.let { codec.archivedRecord(it) }
        }
    }
}
