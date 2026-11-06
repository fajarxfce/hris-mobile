package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceSetupCommandRepository
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
class EncryptedCollaborationSpaceSetupCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") credentials: AtomicCredentialStore,
) : CollaborationSpaceSetupCommandRepository {
    private val keys = CollaborationSpaceCommandKeys(commands, cipher, credentials)
    private val insertions = Mutex()

    override fun observeAll(access: CollaborationSpaceLocalAccess) =
        safeStorageFlow(
            flow {
                emitAll(
                    commands.observeAll(
                        spaceSetupCommandScope(access),
                        spaceSetupCommandPartition(access),
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
                else keys.use { key -> rows.map { decode(access, Retained(it), key) } }
            },
        )

    override suspend fun current(access: CollaborationSpaceLocalAccess, nativeSessionId: String) =
        safeStorageCall {
            val rows =
                commands
                    .observe(
                        spaceSetupCommandScope(access),
                        spaceSetupCommandPartition(access),
                        spaceSetupResource(nativeSessionId),
                        limit = 2,
                    )
                    .first()
            require(rows.size <= 1)
            rows.singleOrNull()?.let { raw ->
                keys.use { key ->
                    decode(access, Retained(raw), key).also {
                        require(it.command.binding.nativeSessionId == nativeSessionId)
                    }
                }
            }
        }

    override suspend fun find(access: CollaborationSpaceLocalAccess, commandId: String) =
        safeStorageCall {
            retained(access, commandId)?.let { raw -> keys.use { key -> decode(access, raw, key) } }
        }

    override suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceSetupCommand,
    ) = safeStorageCall {
        require(validCollaborationSpaceSetupCommand(access, command))
        insertions.withLock {
            val scope = spaceSetupCommandScope(access)
            val partition = spaceSetupCommandPartition(access)
            val previous = retained(access, command.id)
            keys.use(create = previous == null) { key ->
                if (previous != null) {
                    require(decode(access, previous, key).command == command)
                    return@use CollaborationSetupInsertion.STORED
                }
                val codec =
                    CollaborationSpaceSetupCommandCodec(
                        access,
                        command.binding.nativeSessionId,
                        cipher,
                        key,
                    )
                val record =
                    CommandRecord(
                        partition,
                        command.id,
                        spaceSetupResource(command.binding.nativeSessionId),
                        command.orderKey(),
                        codec.request(command),
                        codec.outcome(command, 0, CollaborationSpaceSetupOutcome.Pending, 0),
                    )
                currentCoroutineContext().ensureActive()
                when (commands.insert(scope, record)) {
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
        expected: CollaborationSpaceSetupRecord,
        replacement: CollaborationSpaceSetupCommand,
    ) = safeStorageCall {
        require(validCollaborationSpaceSetupHandoff(access, expected, replacement))
        keys.use { key ->
            val scope = spaceSetupCommandScope(access)
            val partition = spaceSetupCommandPartition(access)
            val raw = commands.get(scope, partition, expected.command.id) ?: return@use false
            if (decode(access, Retained(raw), key) != expected) return@use false
            val codec =
                CollaborationSpaceSetupCommandCodec(
                    access,
                    replacement.binding.nativeSessionId,
                    cipher,
                    key,
                )
            val next =
                CommandRecord(
                    partition,
                    replacement.id,
                    raw.resourceId,
                    replacement.orderKey(),
                    codec.request(replacement),
                    codec.outcome(replacement, 0, CollaborationSpaceSetupOutcome.Pending, 0),
                )
            val evidence = codec.handoff(expected, replacement.id)
            currentCoroutineContext().ensureActive()
            commands.archiveAndInsert(scope, raw, evidence, next)
        }
    }

    override suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceSetupRecord,
        outcome: CollaborationSpaceSetupOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(validCollaborationSpaceSetupRecord(access, expected))
        require(expected.revision < 9_007_199_254_740_991L)
        require(validCollaborationSpaceSetupTransition(expected, outcome, attempts))
        keys.use { key ->
            val scope = spaceSetupCommandScope(access)
            val partition = spaceSetupCommandPartition(access)
            val raw = commands.get(scope, partition, expected.command.id) ?: return@use false
            if (decode(access, Retained(raw), key) != expected) return@use false
            val codec =
                CollaborationSpaceSetupCommandCodec(
                    access,
                    expected.command.binding.nativeSessionId,
                    cipher,
                    key,
                )
            val state = codec.outcome(expected.command, expected.revision + 1, outcome, attempts)
            currentCoroutineContext().ensureActive()
            commands.update(scope, partition, expected.command.id, expected.revision, state)
        }
    }

    override suspend fun archiveRetried(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceSetupRecord,
    ) = safeStorageCall {
        require(validCollaborationSpaceSetupRecord(access, expected))
        require(expected.outcome is CollaborationSpaceSetupOutcome.Retried)
        val raw = retained(access, expected.command.id) ?: return@safeStorageCall false
        keys.use { key ->
            if (decode(access, raw, key) != expected) return@use false
            if (raw.evidence != null) return@use true
            currentCoroutineContext().ensureActive()
            // Preserve the exact authenticated outcome as immutable opaque evidence, so replay
            // does not create fresh encryption or lose the original request/receipt.
            commands.archive(spaceSetupCommandScope(access), raw.record, raw.record.state)
        }
    }

    private data class Retained(val record: CommandRecord, val evidence: String? = null)

    private suspend fun retained(access: CollaborationSpaceLocalAccess, id: String): Retained? {
        require(validCollaborationMessageId(id))
        val scope = spaceSetupCommandScope(access)
        val partition = spaceSetupCommandPartition(access)
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
    ): CollaborationSpaceSetupRecord {
        val parent = raw.record.resourceId.removePrefix("setup/")
        require(spaceSetupResource(parent) == raw.record.resourceId)
        val codec = CollaborationSpaceSetupCommandCodec(access, parent, cipher, key)
        val record = codec.decode(raw.record)
        if (raw.evidence == null) return record
        if (raw.evidence == raw.record.state) {
            require(record.outcome is CollaborationSpaceSetupOutcome.Retried)
            return record
        }
        return record.copy(successorId = codec.successor(raw.record, raw.evidence)).also {
            require(validCollaborationSpaceSetupRecord(access, it))
        }
    }
}
