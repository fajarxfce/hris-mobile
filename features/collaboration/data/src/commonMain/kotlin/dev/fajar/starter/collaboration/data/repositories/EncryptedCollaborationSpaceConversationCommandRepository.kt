package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceConversationCommandRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceConversationCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") credentials: AtomicCredentialStore,
) : CollaborationSpaceConversationCommandRepository {
    private val keys = CollaborationSpaceCommandKeys(commands, cipher, credentials)
    private val insertions = Mutex()

    override fun observeAll(access: CollaborationSpaceLocalAccess) =
        safeStorageFlow(
            flow {
                emitAll(
                    commands.observeAll(
                        spaceConversationCommandScope(access),
                        spaceConversationCommandPartition(access),
                    )
                )
            },
            mapValue = { rows ->
                require(
                    rows.size <= 128 &&
                        rows.map { it.id }.distinct().size == rows.size &&
                        rows.map { it.resourceId }.distinct().size == rows.size
                )
                if (rows.isEmpty()) emptyList()
                else
                    keys.use { key ->
                        val codec = CollaborationSpaceConversationCommandCodec(access, cipher, key)
                        rows.map { codec.decode(it) }
                    }
            },
        )

    override suspend fun find(access: CollaborationSpaceLocalAccess, id: String) = safeStorageCall {
        retained(access, id)?.let { raw -> keys.use { key -> decode(access, raw, key) } }
    }

    override suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceConversationCommand,
    ) = safeStorageCall {
        require(validCollaborationSpaceConversationCommand(access, command))
        insertions.withLock {
            val previous = retained(access, command.id)
            require(
                command !is CollaborationSpaceConversationCommand.Room ||
                    command.predecessorId == null ||
                    previous != null
            )
            keys.use(create = previous == null) { key ->
                if (previous != null) {
                    require(decode(access, previous, key).command == command)
                    return@use CollaborationSetupInsertion.STORED
                }
                val codec = CollaborationSpaceConversationCommandCodec(access, cipher, key)
                val record =
                    CommandRecord(
                        spaceConversationCommandPartition(access),
                        command.id,
                        command.resourceId(),
                        command.orderKey(),
                        codec.request(command),
                        codec.outcome(command, 0, CollaborationSpaceConversationOutcome.Pending, 0),
                    )
                currentCoroutineContext().ensureActive()
                when (commands.insert(spaceConversationCommandScope(access), record)) {
                    CommandInsertResult.Stored -> CollaborationSetupInsertion.STORED
                    CommandInsertResult.AlreadyStored -> {
                        val saved = retained(access, command.id)
                        if (saved == null) CollaborationSetupInsertion.SCOPE_CHANGED
                        else {
                            require(decode(access, saved, key).command == command)
                            CollaborationSetupInsertion.STORED
                        }
                    }
                    CommandInsertResult.ResourceOccupied -> CollaborationSetupInsertion.OCCUPIED
                    CommandInsertResult.CapacityExceeded ->
                        CollaborationSetupInsertion.CAPACITY_EXCEEDED
                    CommandInsertResult.ScopeChanged -> CollaborationSetupInsertion.SCOPE_CHANGED
                }
            }
        }
    }

    override suspend fun prepareReplacement(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceConversationCommandRecord,
        replacement: CollaborationSpaceConversationCommand.Room,
    ) = safeStorageCall {
        require(validCollaborationSpaceRoomHandoff(access, expected, replacement))
        keys.use { key ->
            val scope = spaceConversationCommandScope(access)
            val partition = spaceConversationCommandPartition(access)
            val raw = commands.get(scope, partition, expected.command.id) ?: return@use false
            val codec = CollaborationSpaceConversationCommandCodec(access, cipher, key)
            if (codec.decode(raw) != expected) return@use false
            val next =
                CommandRecord(
                    partition,
                    replacement.id,
                    replacement.resourceId(),
                    replacement.orderKey(),
                    codec.request(replacement),
                    codec.outcome(replacement, 0, CollaborationSpaceConversationOutcome.Pending, 0),
                )
            val evidence = codec.handoff(expected, replacement.id)
            currentCoroutineContext().ensureActive()
            commands.archiveAndInsert(scope, raw, evidence, next)
        }
    }

    override suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceConversationCommandRecord,
        outcome: CollaborationSpaceConversationOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(validCollaborationSpaceConversationCommandRecord(access, expected))
        require(expected.revision < 9_007_199_254_740_991L)
        require(validCollaborationSpaceConversationCommandTransition(expected, outcome, attempts))
        keys.use { key ->
            val scope = spaceConversationCommandScope(access)
            val partition = spaceConversationCommandPartition(access)
            val raw = commands.get(scope, partition, expected.command.id) ?: return@use false
            val codec = CollaborationSpaceConversationCommandCodec(access, cipher, key)
            if (codec.decode(raw) != expected) return@use false
            val state = codec.outcome(expected.command, expected.revision + 1, outcome, attempts)
            currentCoroutineContext().ensureActive()
            commands.update(scope, partition, expected.command.id, expected.revision, state)
        }
    }

    override suspend fun archiveRecorded(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceConversationCommandRecord,
    ) = safeStorageCall {
        require(validCollaborationSpaceConversationCommandRecord(access, expected))
        require(expected.outcome is CollaborationSpaceConversationOutcome.Recorded)
        val raw = retained(access, expected.command.id) ?: return@safeStorageCall false
        keys.use { key ->
            if (decode(access, raw, key) != expected) return@use false
            if (raw.evidence != null) return@use true
            currentCoroutineContext().ensureActive()
            commands.archive(spaceConversationCommandScope(access), raw.record, raw.record.state)
        }
    }

    private data class Retained(val record: CommandRecord, val evidence: String? = null)

    private suspend fun retained(access: CollaborationSpaceLocalAccess, id: String): Retained? {
        require(validCollaborationSpaceConversationId(id))
        val scope = spaceConversationCommandScope(access)
        val partition = spaceConversationCommandPartition(access)
        val active = commands.get(scope, partition, id)
        if (active != null) {
            require(active.id == id)
            return Retained(active)
        }
        return commands.archived(scope, partition, id)?.let {
            require(it.record.id == id)
            Retained(it.record, it.evidence)
        }
    }

    private suspend fun decode(
        access: CollaborationSpaceLocalAccess,
        raw: Retained,
        key: ByteArray,
    ): CollaborationSpaceConversationCommandRecord {
        val codec = CollaborationSpaceConversationCommandCodec(access, cipher, key)
        val record = codec.decode(raw.record)
        if (raw.evidence == null) return record
        return when (record.outcome) {
            is CollaborationSpaceConversationOutcome.Recorded -> {
                require(raw.evidence == raw.record.state)
                record
            }
            is CollaborationSpaceConversationOutcome.Fenced ->
                record.copy(successorId = codec.successor(raw.record, raw.evidence)).also {
                    require(validCollaborationSpaceConversationCommandRecord(access, it))
                }
            else -> error("Only terminal conversation evidence can be archived")
        }
    }
}
