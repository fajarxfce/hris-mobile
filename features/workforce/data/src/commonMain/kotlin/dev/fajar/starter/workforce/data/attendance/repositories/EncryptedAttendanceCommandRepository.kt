package dev.fajar.starter.workforce.data.attendance.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import dev.fajar.starter.workforce.data.attendance.dto.*
import dev.fajar.starter.workforce.data.attendance.mappers.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceCommandRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedAttendanceCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : AttendanceCommandRepository {
    private val insertions = Mutex()

    override fun observeAll(access: WorkforceAccess) =
        safeStorageFlow(
                kotlinx.coroutines.flow.flow {
                    emitAll(
                        commands.observeAll(
                            attendanceCommandScope(access),
                            attendanceCommandPartition(access),
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

    override fun observe(access: WorkforceAccess, resourceId: String?) =
        safeStorageFlow(
            commands.observe(
                attendanceCommandScope(access),
                attendanceCommandPartition(access),
                resourceId,
                limit = 50,
            ),
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

    override suspend fun find(access: WorkforceAccess, id: String) = safeStorageCall {
        val raw =
            commands.get(attendanceCommandScope(access), attendanceCommandPartition(access), id)
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
                    attendanceCommandScope(access),
                    attendanceCommandPartition(access),
                    after = cursor,
                    limit = 50,
                )
                .first()
        if (rows.isEmpty()) AttendanceCommandPage(emptyList(), null)
        else {
            val key = readContentKey()
            try {
                AttendanceCommandPage(
                    rows.map { decryptRecord(access, it, key) },
                    rows.last().sortKey.takeIf { rows.size == 50 },
                )
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun insert(access: WorkforceAccess, command: AttendanceCommand) =
        safeStorageCall {
            insertions.withLock {
                val dto = command.toDto()
                require(dto.toCommand(access) == command)
                val scope = attendanceCommandScope(access)
                val partition = attendanceCommandPartition(access)
                val previous = commands.get(scope, partition, command.id)
                val key = if (previous == null) acquireContentKey() else readContentKey()
                try {
                    if (previous != null) {
                        require(decryptRecord(access, previous, key).command == command)
                        AttendanceCommandAdmission.STORED
                    } else {
                        val requestBytes = Json.encodeToString(dto).encodeToByteArray()
                        val request =
                            try {
                                cipher.encrypt(
                                    key,
                                    attendanceCommandAssociatedData(
                                        access,
                                        command.id,
                                        command.resourceId,
                                        "request",
                                        0,
                                    ),
                                    requestBytes,
                                )
                            } finally {
                                requestBytes.fill(0)
                            }
                        val stateBytes =
                            Json.encodeToString(AttendanceCommandOutcome.Pending.toDto(0))
                                .encodeToByteArray()
                        val state =
                            try {
                                cipher.encrypt(
                                    key,
                                    attendanceCommandAssociatedData(
                                        access,
                                        command.id,
                                        command.resourceId,
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
                                    command.resourceId,
                                    command.orderKey(),
                                    Base64.encode(request),
                                    Base64.encode(state),
                                ),
                            )
                        ) {
                            CommandInsertResult.Stored,
                            CommandInsertResult.AlreadyStored -> AttendanceCommandAdmission.STORED
                            CommandInsertResult.ResourceOccupied ->
                                AttendanceCommandAdmission.OCCUPIED
                            CommandInsertResult.CapacityExceeded ->
                                AttendanceCommandAdmission.CAPACITY_EXCEEDED
                            CommandInsertResult.ScopeChanged ->
                                AttendanceCommandAdmission.SCOPE_CHANGED
                        }
                    }
                } finally {
                    key.fill(0)
                }
            }
        }

    override suspend fun update(
        access: WorkforceAccess,
        expected: AttendanceCommandRecord,
        outcome: AttendanceCommandOutcome,
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
                    attendanceCommandAssociatedData(
                        access,
                        expected.command.id,
                        expected.command.resourceId,
                        "outcome",
                        expected.revision + 1,
                    ),
                    bytes,
                )
            commands.update(
                attendanceCommandScope(access),
                attendanceCommandPartition(access),
                expected.command.id,
                expected.revision,
                Base64.encode(sealed),
            )
        } finally {
            bytes.fill(0)
            key.fill(0)
        }
    }

    override suspend fun remove(access: WorkforceAccess, expected: AttendanceCommandRecord) =
        safeStorageCall {
            commands.remove(
                attendanceCommandScope(access),
                attendanceCommandPartition(access),
                expected.command.id,
                expected.revision,
            )
        }

    /** DTO decoding and both authenticated envelopes stay within the calling storage boundary. */
    private suspend fun decryptRecord(
        access: WorkforceAccess,
        raw: CommandRecord,
        key: ByteArray,
    ): AttendanceCommandRecord {
        val request =
            cipher.decrypt(
                key,
                attendanceCommandAssociatedData(access, raw.id, raw.resourceId, "request", 0),
                Base64.decode(raw.request),
            )
        val command =
            try {
                Json.decodeFromString<AttendanceCommandDto>(
                        request.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toCommand(access)
            } finally {
                request.fill(0)
            }
        val outcome =
            cipher.decrypt(
                key,
                attendanceCommandAssociatedData(
                    access,
                    raw.id,
                    raw.resourceId,
                    "outcome",
                    raw.revision,
                ),
                Base64.decode(raw.state),
            )
        return try {
            Json.decodeFromString<AttendanceCommandOutcomeDto>(
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
