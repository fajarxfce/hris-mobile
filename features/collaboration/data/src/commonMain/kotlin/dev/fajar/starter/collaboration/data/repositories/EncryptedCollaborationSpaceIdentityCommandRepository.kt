package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIdentityCommandRepository
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
class EncryptedCollaborationSpaceIdentityCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") credentials: AtomicCredentialStore,
) : CollaborationSpaceIdentityCommandRepository {
    private val keys = CollaborationSpaceCommandKeys(commands, cipher, credentials)
    private val insertions = Mutex()

    override fun observe(access: CollaborationSpaceLocalAccess) =
        safeStorageFlow(
            flow {
                emitAll(
                    commands.observe(
                        spaceIdentityCommandScope(access),
                        spaceIdentityCommandPartition(access),
                        "identity",
                        limit = 2,
                    )
                )
            },
            mapValue = { rows -> decode(access, rows) },
        )

    override suspend fun current(access: CollaborationSpaceLocalAccess) = safeStorageCall {
        decode(
            access,
            commands
                .observe(
                    spaceIdentityCommandScope(access),
                    spaceIdentityCommandPartition(access),
                    "identity",
                    limit = 2,
                )
                .first(),
        )
    }

    override suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceIdentityCommand,
    ) = safeStorageCall {
        require(validCollaborationSpaceIdentityCommand(access, command))
        insertions.withLock {
            val scope = spaceIdentityCommandScope(access)
            val partition = spaceIdentityCommandPartition(access)
            val previous = commands.get(scope, partition, command.id)
            keys.use(create = previous == null) { key ->
                val codec = CollaborationSpaceIdentityCommandCodec(access, cipher, key)
                if (previous != null) {
                    require(codec.decode(previous).command == command)
                    return@use CollaborationSetupInsertion.STORED
                }
                val record =
                    CommandRecord(
                        partition,
                        command.id,
                        "identity",
                        command.orderKey(),
                        codec.request(command),
                        codec.outcome(command, 0, CollaborationSpaceIdentityOutcome.Pending, 0),
                    )
                currentCoroutineContext().ensureActive()
                when (commands.insert(scope, record)) {
                    CommandInsertResult.Stored -> CollaborationSetupInsertion.STORED
                    CommandInsertResult.AlreadyStored -> {
                        val retained = commands.get(scope, partition, command.id)
                        if (retained == null) CollaborationSetupInsertion.SCOPE_CHANGED
                        else {
                            require(codec.decode(retained).command == command)
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

    override suspend fun update(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceIdentityCommandRecord,
        outcome: CollaborationSpaceIdentityOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(validCollaborationSpaceIdentityRecord(access, expected))
        require(expected.revision < 9_007_199_254_740_991L)
        require(validCollaborationSpaceIdentityTransition(expected, outcome, attempts))
        keys.use { key ->
            val scope = spaceIdentityCommandScope(access)
            val partition = spaceIdentityCommandPartition(access)
            val raw = commands.get(scope, partition, expected.command.id) ?: return@use false
            val codec = CollaborationSpaceIdentityCommandCodec(access, cipher, key)
            val current = codec.decode(raw)
            if (current != expected) return@use false
            val state = codec.outcome(expected.command, expected.revision + 1, outcome, attempts)
            currentCoroutineContext().ensureActive()
            commands.update(scope, partition, expected.command.id, expected.revision, state)
        }
    }

    private suspend fun decode(
        access: CollaborationSpaceLocalAccess,
        rows: List<CommandRecord>,
    ): CollaborationSpaceIdentityCommandRecord? {
        require(rows.size <= 1)
        return rows.singleOrNull()?.let { raw ->
            keys.use { key ->
                CollaborationSpaceIdentityCommandCodec(access, cipher, key).decode(raw)
            }
        }
    }
}
