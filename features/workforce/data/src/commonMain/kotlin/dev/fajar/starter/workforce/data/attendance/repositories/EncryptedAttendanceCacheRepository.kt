package dev.fajar.starter.workforce.data.attendance.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.storage.*
import dev.fajar.starter.workforce.data.attendance.encoding.*
import dev.fajar.starter.workforce.data.attendance.mappers.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceCacheRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import kotlin.io.encoding.Base64
import org.koin.core.annotation.*

@Single
class EncryptedAttendanceCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
) : AttendanceCacheRepository {
    override fun observe(access: WorkforceAccess, window: AttendanceWindow) =
        safeStorageFlow(
            replicas.observe(access.sessionId, attendancePartition(access, window), limit = 33),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Attendance key is unavailable." }
                        )
                    try {
                        decodeAttendanceCache(page, cipher, key, access, window)
                            .toSnapshot(access, checkpoint.handle.generation, window)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun generation(access: WorkforceAccess, window: AttendanceWindow) =
        safeStorageCall {
            replicas
                .checkpoint(access.sessionId, attendancePartition(access, window))
                ?.handle
                ?.generation
        }

    override suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: AttendanceSnapshot?,
        window: AttendanceWindow,
    ) = safeStorageCall {
        val partition = attendancePartition(access, window)
        val previous = replicas.checkpoint(access.sessionId, partition)
        if (previous?.handle?.generation != expectedGeneration) return@safeStorageCall false
        if (snapshot == null)
            return@safeStorageCall replicas.replace(access.sessionId, partition, previous, null)
        require(
            sameWorkforceScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto()
        dto.toSnapshot(access, snapshot.generation, window)
        // Only workspace admission may create this key. Readers never replace a lost one.
        val key = Base64.decode(requireNotNull(credentials.read())).also { require(it.size == 32) }
        try {
            val encoded =
                encodeAttendanceCache(dto, snapshot.generation, cipher, key, access, window)
            replicas.replace(access.sessionId, partition, previous, encoded)
        } finally {
            key.fill(0)
        }
    }
}
