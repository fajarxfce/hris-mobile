package dev.fajar.starter.inbox.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.inbox.data.dto.*
import dev.fajar.starter.inbox.data.mappers.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.repositories.InboxCommandRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedInboxCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : InboxCommandRepository {
    private val insertions = Mutex()

    override fun observeAll(access: InboxAccess) =
        safeStorageFlow(
                kotlinx.coroutines.flow.flow {
                    emitAll(
                        commands.observeAll(
                            inboxCommandScope(access),
                            inboxCommandPartition(access),
                        )
                    )
                },
                mapValue = { rows ->
                    if (rows.isEmpty()) emptyList()
                    else {
                        val key = readContentKey()
                        try {
                            rows.map { decryptRecord(access, it, key) }
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(kotlinx.coroutines.Dispatchers.Default)

    override fun observe(access: InboxAccess, itemId: String) =
        safeStorageFlow(
            commands.observe(inboxCommandScope(access), inboxCommandPartition(access), itemId),
            mapValue = { rows ->
                val raw = rows.singleOrNull()
                if (raw == null) null
                else {
                    val key = readContentKey()
                    try {
                        decryptRecord(access, raw, key)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun find(access: InboxAccess, id: String) = safeStorageCall {
        val raw = commands.get(inboxCommandScope(access), inboxCommandPartition(access), id)
        if (raw == null) null
        else {
            val key = readContentKey()
            try {
                decryptRecord(access, raw, key)
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun page(access: InboxAccess, cursor: String?) = safeStorageCall {
        val rows =
            commands
                .observe(
                    inboxCommandScope(access),
                    inboxCommandPartition(access),
                    after = cursor,
                    limit = 50,
                )
                .first()
        if (rows.isEmpty()) InboxCommandPage(emptyList(), null)
        else {
            val key = readContentKey()
            try {
                InboxCommandPage(
                    rows.map { decryptRecord(access, it, key) },
                    rows.last().sortKey.takeIf { rows.size == 50 },
                )
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun insert(access: InboxAccess, command: InboxCommand) = safeStorageCall {
        insertions.withLock {
            val dto = command.toDto()
            require(dto.toCommand(access) == command)
            val scope = inboxCommandScope(access)
            val partition = inboxCommandPartition(access)
            val previous = commands.get(scope, partition, command.id)
            val key = if (previous == null) acquireContentKey() else readContentKey()
            try {
                if (previous != null) {
                    require(decryptRecord(access, previous, key).command == command)
                    InboxCommandAdmission.STORED
                } else {
                    val requestBytes = Json.encodeToString(dto).encodeToByteArray()
                    val request =
                        try {
                            cipher.encrypt(
                                key,
                                inboxCommandAssociatedData(
                                    access,
                                    command.id,
                                    command.itemId,
                                    "request",
                                    0,
                                ),
                                requestBytes,
                            )
                        } finally {
                            requestBytes.fill(0)
                        }
                    val stateBytes =
                        Json.encodeToString(InboxCommandOutcome.Pending.toDto(0))
                            .encodeToByteArray()
                    val state =
                        try {
                            cipher.encrypt(
                                key,
                                inboxCommandAssociatedData(
                                    access,
                                    command.id,
                                    command.itemId,
                                    "outcome",
                                    0,
                                ),
                                stateBytes,
                            )
                        } finally {
                            stateBytes.fill(0)
                        }
                    when (
                        commands.insert(
                            scope,
                            CommandRecord(
                                partition,
                                command.id,
                                command.itemId,
                                command.orderKey(),
                                Base64.encode(request),
                                Base64.encode(state),
                            ),
                        )
                    ) {
                        CommandInsertResult.Stored,
                        CommandInsertResult.AlreadyStored -> InboxCommandAdmission.STORED
                        CommandInsertResult.ResourceOccupied -> InboxCommandAdmission.OCCUPIED
                        CommandInsertResult.CapacityExceeded ->
                            InboxCommandAdmission.CAPACITY_EXCEEDED
                        CommandInsertResult.ScopeChanged -> InboxCommandAdmission.SCOPE_CHANGED
                    }
                }
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun update(
        access: InboxAccess,
        expected: InboxCommandRecord,
        outcome: InboxCommandOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(expected.command.toDto().toCommand(access) == expected.command)
        require(expected.revision in 0 until 9_007_199_254_740_991L)
        val key = readContentKey()
        val bytes = Json.encodeToString(outcome.toDto(attempts)).encodeToByteArray()
        try {
            val sealed =
                cipher.encrypt(
                    key,
                    inboxCommandAssociatedData(
                        access,
                        expected.command.id,
                        expected.command.itemId,
                        "outcome",
                        expected.revision + 1,
                    ),
                    bytes,
                )
            commands.update(
                inboxCommandScope(access),
                inboxCommandPartition(access),
                expected.command.id,
                expected.revision,
                Base64.encode(sealed),
            )
        } finally {
            bytes.fill(0)
            key.fill(0)
        }
    }

    override suspend fun remove(access: InboxAccess, expected: InboxCommandRecord) =
        safeStorageCall {
            commands.remove(
                inboxCommandScope(access),
                inboxCommandPartition(access),
                expected.command.id,
                expected.revision,
            )
        }

    /** DTO decoding and both authenticated envelopes stay within the calling storage boundary. */
    private suspend fun decryptRecord(
        access: InboxAccess,
        raw: CommandRecord,
        key: ByteArray,
    ): InboxCommandRecord {
        val request =
            cipher.decrypt(
                key,
                inboxCommandAssociatedData(access, raw.id, raw.resourceId, "request", 0),
                Base64.decode(raw.request),
            )
        val command =
            try {
                Json.decodeFromString<InboxCommandDto>(
                        request.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toCommand(access)
            } finally {
                request.fill(0)
            }
        val outcome =
            cipher.decrypt(
                key,
                inboxCommandAssociatedData(access, raw.id, raw.resourceId, "outcome", raw.revision),
                Base64.decode(raw.state),
            )
        return try {
            Json.decodeFromString<InboxCommandOutcomeDto>(
                    outcome.decodeToString(throwOnInvalidSequence = true)
                )
                .toRecord(command, raw, access)
        } finally {
            outcome.fill(0)
        }
    }

    private suspend fun readContentKey(): ByteArray =
        Base64.decode(requireNotNull(credentials.read()) { "Command content key is unavailable." })
            .also { require(it.size == 32) }

    /** Technical storage strategy only; a lost key never replaces retained ciphertext. */
    private suspend fun acquireContentKey(): ByteArray {
        val existing = credentials.read()
        if (existing != null) return Base64.decode(existing).also { require(it.size == 32) }
        if (commands.containsRecords()) return readContentKey()
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
