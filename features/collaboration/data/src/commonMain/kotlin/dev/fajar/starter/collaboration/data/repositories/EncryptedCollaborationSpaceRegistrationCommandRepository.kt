package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRegistrationCommandRepository
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
class EncryptedCollaborationSpaceRegistrationCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") credentials: AtomicCredentialStore,
) : CollaborationSpaceRegistrationCommandRepository {
    private val keys = CollaborationSpaceCommandKeys(commands, cipher, credentials)
    private val insertions = Mutex()

    override fun observeAll(access: CollaborationSpaceLocalAccess) =
        safeStorageFlow(
            flow {
                emitAll(
                    commands.observeAll(
                        spaceRegistrationCommandScope(access),
                        spaceRegistrationCommandPartition(access),
                    )
                )
            },
            mapValue = { rows ->
                require(rows.size <= 128)
                require(rows.map { it.id }.distinct().size == rows.size)
                require(rows.map { it.resourceId }.distinct().size == rows.size)
                if (rows.isEmpty()) emptyList()
                else
                    keys.use { key ->
                        rows.map { raw ->
                            val parent = raw.resourceId.removePrefix("registration/")
                            require(spaceRegistrationResource(parent) == raw.resourceId)
                            CollaborationSpaceRegistrationCommandCodec(access, parent, cipher, key)
                                .decode(raw)
                        }
                    }
            },
        )

    override fun observe(access: CollaborationSpaceLocalAccess, nativeSessionId: String) =
        safeStorageFlow(
            flow {
                emitAll(
                    commands.observe(
                        spaceRegistrationCommandScope(access),
                        spaceRegistrationCommandPartition(access),
                        spaceRegistrationResource(nativeSessionId),
                        limit = 2,
                    )
                )
            },
            mapValue = { rows -> decode(access, nativeSessionId, rows) },
        )

    override suspend fun current(access: CollaborationSpaceLocalAccess, nativeSessionId: String) =
        safeStorageCall {
            decode(
                access,
                nativeSessionId,
                commands
                    .observe(
                        spaceRegistrationCommandScope(access),
                        spaceRegistrationCommandPartition(access),
                        spaceRegistrationResource(nativeSessionId),
                        limit = 2,
                    )
                    .first(),
            )
        }

    override suspend fun insert(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceRegistrationCommand,
    ) = safeStorageCall {
        require(validCollaborationSpaceRegistrationCommand(access, command))
        insertions.withLock {
            val scope = spaceRegistrationCommandScope(access)
            val partition = spaceRegistrationCommandPartition(access)
            val previous = commands.get(scope, partition, command.id)
            keys.use(create = previous == null) { key ->
                val codec =
                    CollaborationSpaceRegistrationCommandCodec(
                        access,
                        command.binding.nativeSessionId,
                        cipher,
                        key,
                    )
                if (previous != null) {
                    require(codec.decode(previous).command == command)
                    return@use CollaborationSetupInsertion.STORED
                }
                val record =
                    CommandRecord(
                        partition,
                        command.id,
                        spaceRegistrationResource(command.binding.nativeSessionId),
                        command.orderKey(),
                        codec.request(command),
                        codec.outcome(command, 0, CollaborationSpaceRegistrationOutcome.Pending, 0),
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
        expected: CollaborationSpaceRegistrationRecord,
        outcome: CollaborationSpaceRegistrationOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(validCollaborationSpaceRegistrationRecord(access, expected))
        require(expected.revision < 9_007_199_254_740_991L)
        require(validCollaborationSpaceRegistrationTransition(expected, outcome, attempts))
        keys.use { key ->
            val scope = spaceRegistrationCommandScope(access)
            val partition = spaceRegistrationCommandPartition(access)
            val raw = commands.get(scope, partition, expected.command.id) ?: return@use false
            val codec =
                CollaborationSpaceRegistrationCommandCodec(
                    access,
                    expected.command.binding.nativeSessionId,
                    cipher,
                    key,
                )
            val current = codec.decode(raw)
            if (current != expected) return@use false
            val state = codec.outcome(expected.command, expected.revision + 1, outcome, attempts)
            currentCoroutineContext().ensureActive()
            commands.update(scope, partition, expected.command.id, expected.revision, state)
        }
    }

    private suspend fun decode(
        access: CollaborationSpaceLocalAccess,
        nativeSessionId: String,
        rows: List<CommandRecord>,
    ): CollaborationSpaceRegistrationRecord? {
        require(rows.size <= 1)
        return rows.singleOrNull()?.let { raw ->
            keys.use { key ->
                CollaborationSpaceRegistrationCommandCodec(access, nativeSessionId, cipher, key)
                    .decode(raw)
            }
        }
    }
}
