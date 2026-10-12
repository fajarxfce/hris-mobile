package dev.fajar.starter.workforce.data.overtime.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeCommandRepository
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedOvertimeCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : OvertimeCommandRepository {
    private val insertions = Mutex()

    override fun observe(access: WorkforceAccess, slot: OvertimeCommandSlot) =
        safeStorageFlow(
            commands.observe(
                overtimeCommandScope(access),
                overtimeCommandPartition(access),
                slot.storageKey(),
            ),
            mapValue = { rows ->
                require(rows.size <= 1)
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

    override suspend fun find(access: WorkforceAccess, id: String) = safeStorageCall {
        val raw = commands.get(overtimeCommandScope(access), overtimeCommandPartition(access), id)
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

    override suspend fun page(access: WorkforceAccess, cursor: String?) = safeStorageCall {
        val rows =
            commands
                .observe(
                    overtimeCommandScope(access),
                    overtimeCommandPartition(access),
                    after = cursor,
                    limit = 50,
                )
                .first()
        require(rows.size <= 50)
        if (rows.isEmpty()) OvertimePage(emptyList(), null)
        else {
            val key = readContentKey()
            try {
                OvertimePage(
                    rows.map { decryptRecord(access, it, key) },
                    rows.last().sortKey.takeIf { rows.size == 50 },
                )
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun insert(access: WorkforceAccess, command: OvertimeCommand) =
        safeStorageCall {
            insertions.withLock {
                val dto = command.toDto()
                require(dto.toCommand() == command && command.belongsTo(access))
                val scope = overtimeCommandScope(access)
                val partition = overtimeCommandPartition(access)
                val previous = commands.get(scope, partition, command.id)
                val key = if (previous == null) acquireContentKey() else readContentKey()
                try {
                    if (previous != null) {
                        require(decryptRecord(access, previous, key).command == command)
                        OvertimeCommandAdmission.STORED
                    } else {
                        val requestBytes = Json.encodeToString(dto).encodeToByteArray()
                        val request =
                            try {
                                require(requestBytes.size <= 16_384)
                                cipher.encrypt(
                                    key,
                                    overtimeCommandAssociatedData(
                                        access,
                                        command.id,
                                        command.slot().storageKey(),
                                        "request",
                                        0,
                                    ),
                                    requestBytes,
                                )
                            } finally {
                                requestBytes.fill(0)
                            }
                        val stateBytes =
                            Json.encodeToString(OvertimeCommandOutcome.Pending.toDto(0))
                                .encodeToByteArray()
                        val state =
                            try {
                                require(stateBytes.size <= 4_096)
                                cipher.encrypt(
                                    key,
                                    overtimeCommandAssociatedData(
                                        access,
                                        command.id,
                                        command.slot().storageKey(),
                                        "outcome",
                                        0,
                                    ),
                                    stateBytes,
                                )
                            } finally {
                                stateBytes.fill(0)
                            }
                        currentCoroutineContext().ensureActive()
                        when (
                            commands.insert(
                                scope,
                                CommandRecord(
                                    partition,
                                    command.id,
                                    command.slot().storageKey(),
                                    command.orderKey(),
                                    Base64.encode(request),
                                    Base64.encode(state),
                                ),
                            )
                        ) {
                            CommandInsertResult.Stored,
                            CommandInsertResult.AlreadyStored -> OvertimeCommandAdmission.STORED
                            CommandInsertResult.ResourceOccupied ->
                                OvertimeCommandAdmission.OCCUPIED
                            CommandInsertResult.CapacityExceeded ->
                                OvertimeCommandAdmission.CAPACITY_EXCEEDED
                            CommandInsertResult.ScopeChanged ->
                                OvertimeCommandAdmission.SCOPE_CHANGED
                        }
                    }
                } finally {
                    key.fill(0)
                }
            }
        }

    override suspend fun update(
        access: WorkforceAccess,
        expected: OvertimeCommandRecord,
        outcome: OvertimeCommandOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(
            expected.command.toDto().toCommand() == expected.command &&
                expected.command.belongsTo(access)
        )
        require(expected.revision in 0 until 9_007_199_254_740_991L)
        val encoded = outcome.toDto(attempts)
        encoded.toOutcome(expected.command.expectedReceiptVersion())
        val key = readContentKey()
        val bytes = Json.encodeToString(encoded).encodeToByteArray()
        try {
            require(bytes.size <= 4_096)
            val sealed =
                cipher.encrypt(
                    key,
                    overtimeCommandAssociatedData(
                        access,
                        expected.command.id,
                        expected.command.slot().storageKey(),
                        "outcome",
                        expected.revision + 1,
                    ),
                    bytes,
                )
            currentCoroutineContext().ensureActive()
            commands.update(
                overtimeCommandScope(access),
                overtimeCommandPartition(access),
                expected.command.id,
                expected.revision,
                Base64.encode(sealed),
            )
        } finally {
            bytes.fill(0)
            key.fill(0)
        }
    }

    override suspend fun remove(access: WorkforceAccess, expected: OvertimeCommandRecord) =
        safeStorageCall {
            require(
                expected.command.toDto().toCommand() == expected.command &&
                    expected.command.belongsTo(access)
            )
            commands.remove(
                overtimeCommandScope(access),
                overtimeCommandPartition(access),
                expected.command.id,
                expected.revision,
            )
        }

    /** DTO decoding and both authenticated envelopes stay within the calling storage boundary. */
    private suspend fun decryptRecord(
        access: WorkforceAccess,
        raw: CommandRecord,
        key: ByteArray,
    ): OvertimeCommandRecord {
        require(raw.request.length <= 24_576 && raw.state.length <= 8_192)
        val request =
            cipher.decrypt(
                key,
                overtimeCommandAssociatedData(access, raw.id, raw.resourceId, "request", 0),
                Base64.decode(raw.request),
            )
        val command =
            try {
                require(request.size <= 16_384)
                Json.decodeFromString<OvertimeCommandDto>(
                        request.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toCommand()
            } finally {
                request.fill(0)
            }
        val outcome =
            cipher.decrypt(
                key,
                overtimeCommandAssociatedData(
                    access,
                    raw.id,
                    raw.resourceId,
                    "outcome",
                    raw.revision,
                ),
                Base64.decode(raw.state),
            )
        return try {
            require(outcome.size <= 4_096)
            Json.decodeFromString<OvertimeCommandOutcomeDto>(
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
