package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationConversationCommandRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationConversationCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationConversationCommandRepository {
    private val insertions = Mutex()

    override suspend fun current(owner: CollaborationOwner, resourceId: String) = safeStorageCall {
        conversationCommandResource(resourceId)
        val rows =
            commands
                .observe(
                    collaborationCommandScope(owner),
                    conversationCommandPartition(owner),
                    resourceId,
                    limit = 2,
                )
                .first()
        require(rows.size <= 1)
        rows.singleOrNull()?.let { raw -> withKey { decrypt(owner, raw, it) } }
    }

    override suspend fun get(owner: CollaborationOwner, commandId: String) = safeStorageCall {
        collaborationId(commandId)
        commands
            .get(collaborationCommandScope(owner), conversationCommandPartition(owner), commandId)
            ?.let { raw -> withKey { decrypt(owner, raw, it) } }
    }

    override suspend fun insert(
        owner: CollaborationOwner,
        command: CollaborationConversationCommand,
    ) = safeStorageCall {
        insertions.withLock {
            val dto = command.toDto()
            require(dto.toCommand(owner) == command)
            val scope = collaborationCommandScope(owner)
            val partition = conversationCommandPartition(owner)
            val previous = commands.get(scope, partition, command.id)
            val key = if (previous == null) acquireKey() else readKey()
            try {
                if (previous != null) {
                    require(decrypt(owner, previous, key).command == command)
                    CollaborationSetupInsertion.STORED
                } else {
                    val request =
                        seal(
                            owner,
                            command.id,
                            command.action.resourceId(),
                            "request",
                            0,
                            Json.encodeToString(dto),
                            key,
                        )
                    val state =
                        seal(
                            owner,
                            command.id,
                            command.action.resourceId(),
                            "outcome",
                            0,
                            Json.encodeToString(CollaborationConversationOutcome.Pending.toDto(0)),
                            key,
                        )
                    currentCoroutineContext().ensureActive()
                    when (
                        commands.insert(
                            scope,
                            CommandRecord(
                                partition,
                                command.id,
                                command.action.resourceId(),
                                command.orderKey(),
                                request,
                                state,
                            ),
                        )
                    ) {
                        CommandInsertResult.Stored -> CollaborationSetupInsertion.STORED
                        CommandInsertResult.AlreadyStored -> {
                            val stored = requireNotNull(commands.get(scope, partition, command.id))
                            require(decrypt(owner, stored, key).command == command)
                            CollaborationSetupInsertion.STORED
                        }
                        CommandInsertResult.ResourceOccupied -> CollaborationSetupInsertion.OCCUPIED
                        CommandInsertResult.CapacityExceeded ->
                            CollaborationSetupInsertion.CAPACITY_EXCEEDED
                        CommandInsertResult.ScopeChanged ->
                            CollaborationSetupInsertion.SCOPE_CHANGED
                    }
                }
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun update(
        owner: CollaborationOwner,
        expected: CollaborationConversationCommandRecord,
        outcome: CollaborationConversationOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(expected.command.toDto().toCommand(owner) == expected.command)
        require(expected.revision in 0 until 9_007_199_254_740_991L)
        val dto = outcome.toDto(attempts)
        dto.toOutcome(owner, expected.command)
        withKey { key ->
            if (!matches(owner, expected, key)) false
            else {
                val state =
                    seal(
                        owner,
                        expected.command.id,
                        expected.command.action.resourceId(),
                        "outcome",
                        expected.revision + 1,
                        Json.encodeToString(dto),
                        key,
                    )
                currentCoroutineContext().ensureActive()
                commands.update(
                    collaborationCommandScope(owner),
                    conversationCommandPartition(owner),
                    expected.command.id,
                    expected.revision,
                    state,
                )
            }
        }
    }

    override suspend fun remove(
        owner: CollaborationOwner,
        expected: CollaborationConversationCommandRecord,
    ) = safeStorageCall {
        require(expected.command.toDto().toCommand(owner) == expected.command)
        withKey { key ->
            if (!matches(owner, expected, key)) false
            else {
                currentCoroutineContext().ensureActive()
                commands.remove(
                    collaborationCommandScope(owner),
                    conversationCommandPartition(owner),
                    expected.command.id,
                    expected.revision,
                )
            }
        }
    }

    private suspend fun matches(
        owner: CollaborationOwner,
        expected: CollaborationConversationCommandRecord,
        key: ByteArray,
    ): Boolean {
        val raw =
            commands.get(
                collaborationCommandScope(owner),
                conversationCommandPartition(owner),
                expected.command.id,
            ) ?: return false
        if (raw.revision != expected.revision) return false
        return decrypt(owner, raw, key) == expected
    }

    private suspend fun decrypt(
        owner: CollaborationOwner,
        raw: CommandRecord,
        key: ByteArray,
    ): CollaborationConversationCommandRecord {
        require(raw.partition == conversationCommandPartition(owner))
        conversationCommandResource(raw.resourceId)
        require(raw.revision in 0..9_007_199_254_740_991L)
        val command =
            Json.decodeFromString<CollaborationConversationCommandDto>(
                    open(owner, raw.id, raw.resourceId, "request", 0, raw.request, key)
                )
                .toCommand(owner)
        require(
            command.id == raw.id &&
                raw.sortKey == command.orderKey() &&
                command.action.resourceId() == raw.resourceId
        )
        val state =
            Json.decodeFromString<CollaborationConversationOutcomeDto>(
                open(owner, raw.id, raw.resourceId, "outcome", raw.revision, raw.state, key)
            )
        return CollaborationConversationCommandRecord(
            command,
            raw.revision,
            state.attempts,
            state.toOutcome(owner, command),
        )
    }

    private suspend fun seal(
        owner: CollaborationOwner,
        id: String,
        resource: String,
        purpose: String,
        revision: Long,
        value: String,
        key: ByteArray,
    ): String {
        val bytes = value.encodeToByteArray()
        return try {
            Base64.encode(
                cipher.encrypt(
                    key,
                    conversationCommandAad(owner, id, resource, purpose, revision),
                    bytes,
                )
            )
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        owner: CollaborationOwner,
        id: String,
        resource: String,
        purpose: String,
        revision: Long,
        value: String,
        key: ByteArray,
    ): String {
        val bytes =
            cipher.decrypt(
                key,
                conversationCommandAad(owner, id, resource, purpose, revision),
                Base64.decode(value),
            )
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun <T> withKey(block: suspend (ByteArray) -> T): T {
        val key = readKey()
        return try {
            block(key)
        } finally {
            key.fill(0)
        }
    }

    private suspend fun readKey() =
        Base64.decode(requireNotNull(credentials.read()) { "Command content key is unavailable." })
            .also { require(it.size == 32) }

    private suspend fun acquireKey(): ByteArray {
        val existing = credentials.read()
        if (existing != null) return Base64.decode(existing).also { require(it.size == 32) }
        if (commands.containsRecords()) return readKey()
        val generated = cipher.generateKey()
        return try {
            Base64.decode(credentials.writeIfAbsent(Base64.encode(generated))).also {
                require(it.size == 32)
            }
        } finally {
            generated.fill(0)
        }
    }
}
