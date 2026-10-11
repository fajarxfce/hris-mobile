package dev.fajar.starter.workforce.data.attendance.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.storage.*
import dev.fajar.starter.workforce.data.attendance.dto.AttendanceSnapshotDto
import dev.fajar.starter.workforce.data.attendance.mappers.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceSnapshot
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceCacheRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedAttendanceCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
) : AttendanceCacheRepository {

    override fun observe(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observeDocument(access.sessionId, attendancePartition(access), "attendance"),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    require(checkpoint.published && checkpoint.revision == 0L)
                    val document = page.documents.single()
                    require(
                        document.id == "attendance" &&
                            document.sortKey == "attendance" &&
                            document.version == 0L
                    )
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Calendar key is unavailable." }
                        )
                    try {
                        val header =
                            cipher.decrypt(
                                key,
                                attendanceAssociatedData(
                                    access,
                                    checkpoint.handle.generation,
                                    "checkpoint",
                                ),
                                Base64.decode(checkpoint.payload),
                            )
                        try {
                            require(header.decodeToString(throwOnInvalidSequence = true) == "v1")
                        } finally {
                            header.fill(0)
                        }
                        val bytes =
                            cipher.decrypt(
                                key,
                                attendanceAssociatedData(
                                    access,
                                    checkpoint.handle.generation,
                                    "attendance",
                                ),
                                Base64.decode(requireNotNull(document.payload)),
                            )
                        try {
                            Json.decodeFromString<AttendanceSnapshotDto>(
                                    bytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toSnapshot(access, checkpoint.handle.generation)
                        } finally {
                            bytes.fill(0)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun generation(access: WorkforceAccess) = safeStorageCall {
        replicas.checkpoint(access.sessionId, attendancePartition(access))?.handle?.generation
    }

    override suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: AttendanceSnapshot?,
    ) = safeStorageCall {
        val partition = attendancePartition(access)
        val previous = replicas.checkpoint(access.sessionId, partition)
        if (previous?.handle?.generation != expectedGeneration) return@safeStorageCall false
        if (snapshot == null)
            return@safeStorageCall replicas.replace(access.sessionId, partition, previous, null)
        require(
            sameWorkforceScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto()
        dto.toSnapshot(access, snapshot.generation)
        // Workspace admission creates this atomic key before publishing an authorized workspace.
        // A reader/writer never replaces a lost key over existing protected data.
        val key = Base64.decode(requireNotNull(credentials.read())).also { require(it.size == 32) }
        try {
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            val document =
                try {
                    cipher.encrypt(
                        key,
                        attendanceAssociatedData(access, snapshot.generation, "attendance"),
                        bytes,
                    )
                } finally {
                    bytes.fill(0)
                }
            val header =
                cipher.encrypt(
                    key,
                    attendanceAssociatedData(access, snapshot.generation, "checkpoint"),
                    "v1".encodeToByteArray(),
                )
            replicas.replace(
                access.sessionId,
                partition,
                previous,
                ReplicaSnapshot(
                    snapshot.generation,
                    Base64.encode(header),
                    listOf(ReplicaDocument("attendance", 0, "attendance", Base64.encode(document))),
                ),
            )
        } finally {
            key.fill(0)
        }
    }
}
