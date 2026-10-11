package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.LeaveCommandRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedLeaveCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : LeaveCommandRepository {
    private val insertions = Mutex()

    override fun observe(access: LeaveAccess, requestId: String) =
        safeStorageFlow(
            commands.observe(leaveCommandScope(access), leaveCommandPartition(access), requestId),
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

    override suspend fun find(access: LeaveAccess, id: String) = safeStorageCall {
        val raw = commands.get(leaveCommandScope(access), leaveCommandPartition(access), id)
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

    override suspend fun page(access: LeaveAccess, cursor: String?) = safeStorageCall {
        val rows =
            commands
                .observe(
                    leaveCommandScope(access),
                    leaveCommandPartition(access),
                    after = cursor,
                    limit = 50,
                )
                .first()
        if (rows.isEmpty()) LeavePage(emptyList(), null)
        else {
            val key = readContentKey()
            try {
                LeavePage(
                    rows.map { decryptRecord(access, it, key) },
                    rows.last().sortKey.takeIf { rows.size == 50 },
                )
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun insert(access: LeaveAccess, command: LeaveCommand) = safeStorageCall {
        insertions.withLock {
            val dto = command.toDto()
            require(dto.toCommand(access) == command)
            val scope = leaveCommandScope(access)
            val partition = leaveCommandPartition(access)
            val previous = commands.get(scope, partition, command.id)
            val key = if (previous == null) acquireContentKey() else readContentKey()
            try {
                if (previous != null) {
                    require(decryptRecord(access, previous, key).command == command)
                    LeaveCommandAdmission.STORED
                } else {
                    val requestBytes = Json.encodeToString(dto).encodeToByteArray()
                    val request =
                        try {
                            cipher.encrypt(
                                key,
                                leaveCommandAssociatedData(
                                    access,
                                    command.id,
                                    command.requestId,
                                    "request",
                                    0,
                                ),
                                requestBytes,
                            )
                        } finally {
                            requestBytes.fill(0)
                        }
                    val stateBytes =
                        Json.encodeToString(LeaveCommandOutcome.Pending.toDto(0))
                            .encodeToByteArray()
                    val state =
                        try {
                            cipher.encrypt(
                                key,
                                leaveCommandAssociatedData(
                                    access,
                                    command.id,
                                    command.requestId,
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
                                command.requestId,
                                command.orderKey(),
                                Base64.encode(request),
                                Base64.encode(state),
                            ),
                        )
                    ) {
                        CommandInsertResult.Stored,
                        CommandInsertResult.AlreadyStored -> LeaveCommandAdmission.STORED
                        CommandInsertResult.ResourceOccupied -> LeaveCommandAdmission.OCCUPIED
                        CommandInsertResult.CapacityExceeded ->
                            LeaveCommandAdmission.CAPACITY_EXCEEDED
                        CommandInsertResult.ScopeChanged -> LeaveCommandAdmission.SCOPE_CHANGED
                    }
                }
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun update(
        access: LeaveAccess,
        expected: LeaveCommandRecord,
        outcome: LeaveCommandOutcome,
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
                    leaveCommandAssociatedData(
                        access,
                        expected.command.id,
                        expected.command.requestId,
                        "outcome",
                        expected.revision + 1,
                    ),
                    bytes,
                )
            commands.update(
                leaveCommandScope(access),
                leaveCommandPartition(access),
                expected.command.id,
                expected.revision,
                Base64.encode(sealed),
            )
        } finally {
            bytes.fill(0)
            key.fill(0)
        }
    }

    override suspend fun remove(access: LeaveAccess, expected: LeaveCommandRecord) =
        safeStorageCall {
            commands.remove(
                leaveCommandScope(access),
                leaveCommandPartition(access),
                expected.command.id,
                expected.revision,
            )
        }

    /** DTO decoding and both authenticated envelopes stay within the calling storage boundary. */
    private suspend fun decryptRecord(
        access: LeaveAccess,
        raw: CommandRecord,
        key: ByteArray,
    ): LeaveCommandRecord {
        val request =
            cipher.decrypt(
                key,
                leaveCommandAssociatedData(access, raw.id, raw.resourceId, "request", 0),
                Base64.decode(raw.request),
            )
        val command =
            try {
                Json.decodeFromString<LeaveCommandDto>(
                        request.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toCommand(access)
            } finally {
                request.fill(0)
            }
        val outcome =
            cipher.decrypt(
                key,
                leaveCommandAssociatedData(access, raw.id, raw.resourceId, "outcome", raw.revision),
                Base64.decode(raw.state),
            )
        return try {
            Json.decodeFromString<LeaveCommandOutcomeDto>(
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
