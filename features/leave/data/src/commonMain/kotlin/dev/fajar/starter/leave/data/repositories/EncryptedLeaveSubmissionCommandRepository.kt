package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.belongsTo
import dev.fajar.starter.leave.domain.repositories.LeaveSubmissionCommandRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedLeaveSubmissionCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : LeaveSubmissionCommandRepository {
    private val insertions = Mutex()

    override fun observe(access: LeaveAccess, employeeId: String) =
        safeStorageFlow(
            commands.observe(
                leaveCommandScope(access),
                leaveSubmissionPartition(access),
                employeeId,
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

    override suspend fun find(access: LeaveAccess, id: String) = safeStorageCall {
        val raw = commands.get(leaveCommandScope(access), leaveSubmissionPartition(access), id)
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
                    leaveSubmissionPartition(access),
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

    override suspend fun insert(access: LeaveAccess, command: LeaveSubmissionCommand) =
        safeStorageCall {
            insertions.withLock {
                val dto = command.toDto()
                require(dto.toSubmissionCommand() == command && command.belongsTo(access))
                val scope = leaveCommandScope(access)
                val partition = leaveSubmissionPartition(access)
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
                                    leaveSubmissionAssociatedData(
                                        access,
                                        command.id,
                                        command.selection.employeeId,
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
                                    leaveSubmissionAssociatedData(
                                        access,
                                        command.id,
                                        command.selection.employeeId,
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
                                    command.selection.employeeId,
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
        expected: LeaveSubmissionRecord,
        outcome: LeaveCommandOutcome,
        attempts: Int,
    ) = safeStorageCall {
        require(
            expected.command.toDto().toSubmissionCommand() == expected.command &&
                expected.command.belongsTo(access)
        )
        require(expected.revision in 0 until 9_007_199_254_740_991L)
        val encoded = outcome.toDto(attempts)
        encoded.toOutcome(0)
        val key = readContentKey()
        val bytes = Json.encodeToString(encoded).encodeToByteArray()
        try {
            val sealed =
                cipher.encrypt(
                    key,
                    leaveSubmissionAssociatedData(
                        access,
                        expected.command.id,
                        expected.command.selection.employeeId,
                        "outcome",
                        expected.revision + 1,
                    ),
                    bytes,
                )
            currentCoroutineContext().ensureActive()
            commands.update(
                leaveCommandScope(access),
                leaveSubmissionPartition(access),
                expected.command.id,
                expected.revision,
                Base64.encode(sealed),
            )
        } finally {
            bytes.fill(0)
            key.fill(0)
        }
    }

    override suspend fun remove(access: LeaveAccess, expected: LeaveSubmissionRecord) =
        safeStorageCall {
            commands.remove(
                leaveCommandScope(access),
                leaveSubmissionPartition(access),
                expected.command.id,
                expected.revision,
            )
        }

    /** DTO decoding and both authenticated envelopes stay within the calling storage boundary. */
    private suspend fun decryptRecord(
        access: LeaveAccess,
        raw: CommandRecord,
        key: ByteArray,
    ): LeaveSubmissionRecord {
        val request =
            cipher.decrypt(
                key,
                leaveSubmissionAssociatedData(access, raw.id, raw.resourceId, "request", 0),
                Base64.decode(raw.request),
            )
        val command =
            try {
                Json.decodeFromString<LeaveSubmissionCommandDto>(
                        request.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toSubmissionCommand()
            } finally {
                request.fill(0)
            }
        val outcome =
            cipher.decrypt(
                key,
                leaveSubmissionAssociatedData(
                    access,
                    raw.id,
                    raw.resourceId,
                    "outcome",
                    raw.revision,
                ),
                Base64.decode(raw.state),
            )
        return try {
            Json.decodeFromString<LeaveCommandOutcomeDto>(
                    outcome.decodeToString(throwOnInvalidSequence = true)
                )
                .toSubmissionRecord(command, raw, access)
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
