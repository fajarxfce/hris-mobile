package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.LeaveOverviewDto
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.sameLeaveScope
import dev.fajar.starter.leave.domain.repositories.LeaveCacheRepository
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedLeaveCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("leave-content-key") private val credentials: CredentialStore,
) : LeaveCacheRepository {
    private val keyCreation = Mutex()

    override fun observe(access: LeaveAccess) =
        safeStorageFlow(
            replicas.observeDocument(access.sessionId, leavePartition(access), "overview"),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    require(checkpoint.published && checkpoint.revision == 0L)
                    val document = page.documents.single()
                    require(
                        document.id == "overview" &&
                            document.sortKey == "overview" &&
                            document.version == 0L
                    )
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Leave key is unavailable." }
                        )
                    try {
                        val header =
                            cipher.decrypt(
                                key,
                                leaveAssociatedData(
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
                                leaveAssociatedData(
                                    access,
                                    checkpoint.handle.generation,
                                    "overview",
                                ),
                                Base64.decode(requireNotNull(document.payload)),
                            )
                        try {
                            Json.decodeFromString<LeaveOverviewDto>(
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

    override suspend fun generation(access: LeaveAccess) = safeStorageCall {
        replicas.checkpoint(access.sessionId, leavePartition(access))?.handle?.generation
    }

    override suspend fun replace(
        access: LeaveAccess,
        expectedGeneration: String?,
        snapshot: LeaveOverview?,
    ) = safeStorageCall {
        val partition = leavePartition(access)
        val previous = replicas.checkpoint(access.sessionId, partition)
        if (previous?.handle?.generation != expectedGeneration) return@safeStorageCall false
        if (snapshot == null)
            return@safeStorageCall replicas.replace(access.sessionId, partition, previous, null)
        require(
            sameLeaveScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto()
        dto.toSnapshot(access, snapshot.generation)
        val key =
            keyCreation.withLock {
                val saved = credentials.read()
                if (saved != null) Base64.decode(saved)
                else {
                    check(previous == null) { "Existing leave view requires its content key." }
                    val generated = cipher.generateKey()
                    try {
                        withContext(NonCancellable) { credentials.write(Base64.encode(generated)) }
                        generated.copyOf()
                    } finally {
                        generated.fill(0)
                    }
                }
            }
        try {
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            val document =
                try {
                    require(bytes.size <= 96_000)
                    cipher.encrypt(
                        key,
                        leaveAssociatedData(access, snapshot.generation, "overview"),
                        bytes,
                    )
                } finally {
                    bytes.fill(0)
                }
            val header =
                cipher.encrypt(
                    key,
                    leaveAssociatedData(access, snapshot.generation, "checkpoint"),
                    "v1".encodeToByteArray(),
                )
            currentCoroutineContext().ensureActive()
            replicas.replace(
                access.sessionId,
                partition,
                previous,
                ReplicaSnapshot(
                    snapshot.generation,
                    Base64.encode(header),
                    listOf(ReplicaDocument("overview", 0, "overview", Base64.encode(document))),
                ),
            )
        } finally {
            key.fill(0)
        }
    }
}
