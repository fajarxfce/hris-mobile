@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationEditEvidenceRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationEditTargetRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationLocalVisibilityRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageEditRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationQuoteRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationHistoryRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) :
    CollaborationHistoryRepository,
    CollaborationMessageEditRepository,
    CollaborationEditEvidenceRepository,
    CollaborationEditTargetRepository,
    CollaborationLocalVisibilityRepository,
    CollaborationQuoteRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun getEditCandidate(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ) = safeStorageCall {
        validateHistoryRoom(roomId)
        require(validCollaborationEventId(eventId))
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall null
        withKey(access, conversationId, roomId) { codec ->
            codec.checkpoint(raw)
            val value = CollaborationEditTargetReader(replicas, raw, codec, roomId).get(eventId)
            require(replicas.checkpoint(scope, partition) == raw)
            value
        }
    }

    override fun observeQuotes(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ) =
        safeStorageFlow(
                flow {
                    require(validCollaborationQuoteQuery(conversationId, roomId, eventIds))
                    val selected = eventIds.toList()
                    emitAll(
                        replicas
                            .observeCheckpoint(
                                historyScope(access),
                                historyPartition(access, conversationId),
                            )
                            .map { selected to it }
                    )
                },
                mapValue = { (selected, raw) ->
                    if (raw == null || selected.isEmpty()) emptyList()
                    else
                        withKey(access, conversationId, roomId) { codec ->
                            codec.checkpoint(raw)
                            val result = selected.mapNotNull { quote(raw, codec, it) }
                            require(
                                replicas.checkpoint(raw.handle.scope, raw.handle.partition) == raw
                            )
                            result
                        }
                },
            )
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .buffer(0)

    private suspend fun quote(
        raw: ReplicaCheckpoint,
        codec: CollaborationHistoryCodec,
        eventId: String,
    ): CollaborationQuotedMessage? {
        val scope = raw.handle.scope
        val partition = raw.handle.partition
        val aliasId = codec.aliasId(eventId)
        val alias =
            replicas.document(scope, partition, aliasId)?.let {
                require(it.id == aliasId)
                codec.alias(raw, it)
            } ?: return null
        val messageId = alias.messageId ?: return null
        if (alias.replacement) return null
        val message =
            requireNotNull(replicas.document(scope, partition, messageId)).let {
                require(it.id == messageId)
                codec.message(raw, it)
            }
        require(message.sender == alias.sender && eventId in message.eventIds)
        require(!alias.redacted || message.redacted)
        if (message.redacted || message.locallyHidden) return null
        val reference = CollaborationMessageReference(message.sender, eventId, message.logicalId)
        if (hiddenInHistory(replicas, raw, codec, reference)) return null
        return CollaborationQuotedMessage(
            eventId,
            message.sender,
            message.latestEdit?.edit?.text ?: requireNotNull(message.text),
        )
    }

    override fun observeHidden(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        messages: List<CollaborationMessageReference>,
    ) =
        safeStorageFlow(
                flow {
                    validateHistoryRoom(roomId)
                    require(
                        messages.size <= COLLABORATION_VISIBILITY_READ_LIMIT &&
                            messages.distinct().size == messages.size
                    )
                    require(messages.all(::validCollaborationMessageReference))
                    val selected = messages.toList()
                    emitAll(
                        replicas
                            .observeCheckpoint(
                                historyScope(access),
                                historyPartition(access, conversationId),
                            )
                            .map { selected to it }
                    )
                },
                mapValue = { (selected, raw) ->
                    if (raw == null) emptySet()
                    else
                        withKey(access, conversationId, roomId) { codec ->
                            codec.checkpoint(raw)
                            val hidden = linkedSetOf<CollaborationMessageReference>()
                            for (reference in selected) {
                                if (hiddenInHistory(replicas, raw, codec, reference))
                                    hidden += reference
                            }
                            require(
                                replicas.checkpoint(raw.handle.scope, raw.handle.partition) == raw
                            )
                            hidden.toSet()
                        }
                },
            )
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .buffer(0)

    override suspend fun hide(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        messages: List<CollaborationMessageReference>,
    ) = safeStorageCall {
        require(
            messages.size in 1..COLLABORATION_LOCAL_DELETE_LIMIT &&
                messages.distinct().size == messages.size
        )
        require(messages.all(::validCollaborationMessageReference))
        val selected = messages.toList()
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition)
        withKey(access, conversationId, roomId, create = raw == null) { codec ->
            val previous = raw?.let { codec.checkpoint(it) }
            val batch = CollaborationHistoryHideBatch(replicas, scope, partition, raw, codec)
            batch.apply(selected)
            if (!batch.changed) return@withKey replicas.checkpoint(scope, partition) == raw
            val generation = raw?.handle?.generation ?: Uuid.random().toString()
            val revision = raw?.revision?.plus(1) ?: 0
            val hiddenRules = (previous?.hiddenRules ?: 0) + batch.addedRules
            require(hiddenRules in 1..9_007_199_254_740_991L)
            val payload = codec.checkpoint(generation, revision, previous?.count ?: 0, hiddenRules)
            val documents = batch.documents(generation, revision)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(generation, payload, documents),
                )
            else replicas.commit(raw.handle, raw.revision, payload, documents, true)
        }
    }

    override fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String?,
        after: String?,
        limit: Int,
        before: String?,
        overlap: Int,
    ) =
        safeStorageFlow(
                flow {
                    require(limit in 1..150 && overlap in 0..50 && overlap < limit)
                    require(after == null || before == null)
                    require(listOfNotNull(after, before).all(historyCursor::matches))
                    emitAll(
                        replicas.observeQueries(
                            historyScope(access),
                            historyPartition(access, conversationId),
                            listOf(ReplicaQuery(HISTORY_MESSAGES, after, limit + 1, before)),
                        )
                    )
                },
                mapValue = { page ->
                    require(page.pages.size == 1)
                    val documents = page.pages.single()
                    val raw = page.checkpoint
                    if (raw == null) {
                        require(documents.isEmpty())
                        CollaborationHistoryPage(emptyList(), null)
                    } else
                        withKey(access, conversationId, roomId) { codec ->
                            val checkpoint = codec.checkpoint(raw)
                            require(
                                documents.size <= limit + 1 &&
                                    documents.size.toLong() <= checkpoint.count
                            )
                            require(
                                documents.zipWithNext().all { (a, b) ->
                                    if (before == null) a.sortKey < b.sortKey
                                    else a.sortKey > b.sortKey
                                }
                            )
                            require(
                                documents.all {
                                    (after == null || it.sortKey > after) &&
                                        (before == null || it.sortKey < before)
                                }
                            )
                            // Authenticate the lookahead as well as the visible messages.
                            val decoded = documents.map { codec.message(raw, it) }
                            require(decoded.none { it.locallyHidden })
                            val selected =
                                documents.take(limit).let {
                                    if (before == null) it else it.reversed()
                                }
                            val messages =
                                decoded.take(limit).let {
                                    if (before == null) it else it.reversed()
                                }
                            val hasOlder =
                                if (before == null) documents.size > limit
                                else selected.isNotEmpty()
                            val hasNewer =
                                if (before == null) after != null else documents.size > limit
                            val retained =
                                overlap.coerceAtMost((selected.size - 1).coerceAtLeast(0))
                            CollaborationHistoryPage(
                                messages,
                                if (hasOlder) selected[selected.lastIndex - retained].sortKey
                                else null,
                                if (hasNewer && selected.isNotEmpty()) selected[retained].sortKey
                                else null,
                                checkpoint.roomId,
                                raw.handle.generation + ":" + raw.revision,
                            )
                        }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun getByEvent(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ) = safeStorageCall { readEvent(access, conversationId, roomId, eventId)?.second }

    override suspend fun getEventFact(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ) = safeStorageCall {
        readEvent(access, conversationId, roomId, eventId)?.let { (alias, message) ->
            CollaborationHistoryEventFact(
                alias.eventId,
                alias.sender,
                alias.timestamp,
                alias.redacted || message?.redacted == true,
            )
        }
    }

    override suspend fun getEditEvidence(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ) = safeStorageCall {
        require(validCollaborationEventId(eventId))
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall null
        withKey(access, conversationId, roomId) { codec ->
            codec.checkpoint(raw)
            val value = CollaborationEditEvidenceReader(replicas, raw, codec).get(eventId)
            require(replicas.checkpoint(scope, partition) == raw)
            value
        }
    }

    override suspend fun getEditOperationEvidence(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        sender: String,
        operationId: String,
    ) = safeStorageCall {
        require(validCollaborationHistorySender(sender) && validCollaborationMessageId(operationId))
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall emptyList()
        withKey(access, conversationId, roomId) { codec ->
            codec.checkpoint(raw)
            val values =
                CollaborationEditEvidenceReader(replicas, raw, codec).operation(sender, operationId)
            require(replicas.checkpoint(scope, partition) == raw)
            values
        }
    }

    override suspend fun getReplacementFact(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall null
        withKey(access, conversationId, roomId) { codec ->
            codec.checkpoint(raw)
            CollaborationEditEvidenceReader(replicas, raw, codec).get(eventId)?.let { value ->
                require(replicas.checkpoint(scope, partition) == raw)
                return@withKey CollaborationReplacementEventFact(
                    value.eventId,
                    value.sender,
                    value.timestamp,
                    value.redacted,
                )
            }
            val aliasId = codec.aliasId(eventId)
            val alias =
                replicas.document(scope, partition, aliasId)?.let {
                    require(it.id == aliasId)
                    codec.alias(raw, it)
                }
            val fact =
                alias
                    ?.takeIf { it.replacement }
                    ?.let {
                        val id = requireNotNull(it.messageId)
                        val original =
                            codec.message(
                                raw,
                                requireNotNull(replicas.document(scope, partition, id)).also { found
                                    ->
                                    require(found.id == id)
                                },
                            )
                        require(
                            original.sender == it.sender &&
                                it.replacementTarget in original.eventIds
                        )
                        if (!it.redacted) {
                            val revisionId = codec.revisionId(eventId)
                            val revision =
                                codec.revision(
                                    raw,
                                    requireNotNull(replicas.document(scope, partition, revisionId))
                                        .also { found -> require(found.id == revisionId) },
                                )
                            require(revision.messageId == id)
                            val edit = revision.revision.toDomain().edit
                            require(
                                edit.eventId == eventId &&
                                    edit.sender == it.sender &&
                                    edit.timestamp == it.timestamp
                            )
                            require(
                                edit.contentHash == it.replacementHash &&
                                    edit.targetEventId == it.replacementTarget
                            )
                        }
                        CollaborationReplacementEventFact(
                            it.eventId,
                            it.sender,
                            requireNotNull(it.timestamp),
                            it.redacted || original.redacted,
                        )
                    }
            require(replicas.checkpoint(scope, partition) == raw)
            fact
        }
    }

    private suspend fun readEvent(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): Pair<HistoryAliasDto, CollaborationHistoryMessage?>? {
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        // A missing owned partition does not require (or create) a content key.
        val raw = replicas.checkpoint(scope, partition) ?: return null
        return withKey(access, conversationId, roomId) { codec ->
            codec.checkpoint(raw)
            val proofId = codec.editEvidenceId(eventId)
            replicas.document(scope, partition, proofId)?.let {
                require(it.id == proofId)
                codec.editEvidence(raw, it)
                require(replicas.checkpoint(scope, partition) == raw)
                return@withKey null
            }
            val aliasId = codec.aliasId(eventId)
            val alias =
                replicas.document(scope, partition, aliasId)?.let {
                    require(it.id == aliasId)
                    codec.alias(raw, it)
                }
            val message =
                alias
                    ?.takeUnless { it.replacement }
                    ?.messageId
                    ?.let { id ->
                        val document = requireNotNull(replicas.document(scope, partition, id))
                        require(document.id == id)
                        codec.message(raw, document).also {
                            require(it.sender == alias.sender && eventId in it.eventIds)
                            require(!alias.redacted || it.redacted)
                        }
                    }
            // Do not combine different generations/revisions or publish through a late logout.
            require(replicas.checkpoint(scope, partition) == raw)
            alias?.takeUnless { it.replacement }?.let { it to message }
        }
    }

    override suspend fun merge(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        changes: List<CollaborationHistoryChange>,
    ) = safeStorageCall {
        require(changes.size in 1..16)
        val batch = changes.toList()
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition)
        withKey(access, conversationId, roomId, create = raw == null) { codec ->
            val previous = raw?.let { codec.checkpoint(it) }
            val mutation = CollaborationHistoryBatch(replicas, scope, partition, raw, codec)
            mutation.apply(batch)
            val generation = raw?.handle?.generation ?: Uuid.random().toString()
            val revision = raw?.revision?.plus(1) ?: 0
            val count = (previous?.count ?: 0) + mutation.added
            require(count in 0..9_007_199_254_740_991L)
            val documents = mutation.documents(generation, revision)
            currentCoroutineContext().ensureActive()
            if (raw != null && documents.isEmpty())
                return@withKey replicas.checkpoint(scope, partition) == raw
            val checkpoint =
                codec.checkpoint(generation, revision, count, previous?.hiddenRules ?: 0)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(generation, checkpoint, documents),
                )
            else replicas.commit(raw.handle, raw.revision, checkpoint, documents, true)
        }
    }

    override suspend fun getByLogicalId(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        sender: String,
        logicalId: String,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        if (replicas.observePublishedCheckpoint(scope, partition).first() == null)
            return@safeStorageCall null
        withKey(access, conversationId, roomId) { codec ->
            val id = codec.messageId(sender, logicalId, null)
            val page = replicas.observeDocument(scope, partition, id).first()
            val raw = requireNotNull(page.checkpoint)
            codec.checkpoint(raw)
            require(page.documents.size <= 1)
            val message =
                page.documents.singleOrNull()?.let {
                    require(it.id == id)
                    codec.message(raw, it)
                }
            // A concurrent sync may advance this generation while the captured message is decoded.
            // Retain the owned, coherent snapshot; logout or generation replacement still closes
            // it.
            require(
                replicas.observePublishedCheckpoint(scope, partition).first()?.handle == raw.handle
            )
            currentCoroutineContext().ensureActive()
            message
        }
    }

    override suspend fun mergeEdits(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        edits: List<CollaborationMessageEdit>,
    ) = safeStorageCall {
        require(edits.size in 1..16)
        val selected = edits.toList()
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = requireNotNull(replicas.checkpoint(scope, partition))
        withKey(access, conversationId, roomId) { codec ->
            val previous = codec.checkpoint(raw)
            val batch = CollaborationHistoryBatch(replicas, scope, partition, raw, codec)
            batch.applyEdits(selected)
            val revision = raw.revision + 1
            val documents = batch.documents(raw.handle.generation, revision)
            currentCoroutineContext().ensureActive()
            if (documents.isEmpty()) replicas.checkpoint(scope, partition) == raw
            else
                replicas.commit(
                    raw.handle,
                    raw.revision,
                    codec.checkpoint(
                        raw.handle.generation,
                        revision,
                        previous.count,
                        previous.hiddenRules,
                    ),
                    documents,
                    true,
                )
        }
    }

    private suspend fun <T> withKey(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String?,
        create: Boolean = false,
        operation: suspend (CollaborationHistoryCodec) -> T,
    ): T {
        return keys.use(create) { content, index ->
            operation(
                CollaborationHistoryCodec(access, conversationId, roomId, cipher, content, index)
            )
        }
    }
}
