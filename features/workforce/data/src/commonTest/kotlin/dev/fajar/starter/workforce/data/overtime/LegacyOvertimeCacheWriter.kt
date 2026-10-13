package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.storage.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.encoding.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

class LegacyOvertimeCacheWriter(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    private val credentials: AtomicCredentialStore,
) {

    fun observe(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                ReplicaScope(access.sessionId),
                overtimePartition(access, "overview"),
                "overview",
            ),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    val key =
                        Base64.decode(requireNotNull(credentials.read())).also {
                            require(it.size == 32)
                        }
                    try {
                        val bytes = decodeOvertimeCache(page, access, "overview", cipher, key)
                        try {
                            require(bytes.size <= 96_000)
                            Json.decodeFromString<OvertimeSnapshotDto<OvertimeOverviewDto>>(
                                    bytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toSnapshot(access, checkpoint.handle.generation) { value, owner ->
                                    value.toOverview(owner)
                                }
                        } finally {
                            bytes.fill(0)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    suspend fun generation(access: WorkforceAccess) = safeStorageCall {
        replicas
            .checkpoint(ReplicaScope(access.sessionId), overtimePartition(access, "overview"))
            ?.handle
            ?.generation
    }

    suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: OvertimeSnapshot<OvertimeOverview>?,
    ) = safeStorageCall {
        val partition = overtimePartition(access, "overview")
        val previous = replicas.checkpoint(ReplicaScope(access.sessionId), partition)
        if (previous?.handle?.generation != expectedGeneration) return@safeStorageCall false
        if (snapshot == null)
            return@safeStorageCall replicas.replace(
                ReplicaScope(access.sessionId),
                partition,
                previous,
                null,
            )
        require(
            sameWorkforceScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto { it.toDto() }
        dto.toSnapshot(access, snapshot.generation) { value, owner -> value.toOverview(owner) }
        // Workspace admission creates this key; a read view must never replace a missing key.
        val key = Base64.decode(requireNotNull(credentials.read())).also { require(it.size == 32) }
        try {
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            val encoded =
                try {
                    encodeOvertimeCache(bytes, access, snapshot.generation, "overview", cipher, key)
                } finally {
                    bytes.fill(0)
                }
            currentCoroutineContext().ensureActive()
            replicas.replace(ReplicaScope(access.sessionId), partition, previous, encoded)
        } finally {
            key.fill(0)
        }
    }

    fun observeRequest(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                ReplicaScope(access.sessionId),
                overtimePartition(access, "request"),
                "request",
            ),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    val key =
                        Base64.decode(requireNotNull(credentials.read())).also {
                            require(it.size == 32)
                        }
                    try {
                        val bytes = decodeOvertimeCache(page, access, "request", cipher, key)
                        try {
                            require(bytes.size <= 96_000)
                            Json.decodeFromString<OvertimeSnapshotDto<OvertimeRequestCacheDto>>(
                                    bytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toSnapshot(access, checkpoint.handle.generation) { value, owner ->
                                    value.toView()
                                }
                        } finally {
                            bytes.fill(0)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    suspend fun requestGeneration(access: WorkforceAccess) = safeStorageCall {
        replicas
            .checkpoint(ReplicaScope(access.sessionId), overtimePartition(access, "request"))
            ?.handle
            ?.generation
    }

    suspend fun replaceRequest(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: OvertimeSnapshot<OvertimeRequestView>?,
    ) = safeStorageCall {
        val partition = overtimePartition(access, "request")
        val previous = replicas.checkpoint(ReplicaScope(access.sessionId), partition)
        if (previous?.handle?.generation != expectedGeneration) return@safeStorageCall false
        if (snapshot == null)
            return@safeStorageCall replicas.replace(
                ReplicaScope(access.sessionId),
                partition,
                previous,
                null,
            )
        require(
            sameWorkforceScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto { it.toDto() }
        dto.toSnapshot(access, snapshot.generation) { value, owner -> value.toView() }
        // Workspace admission creates this key; a read view must never replace a missing key.
        val key = Base64.decode(requireNotNull(credentials.read())).also { require(it.size == 32) }
        try {
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            val encoded =
                try {
                    encodeOvertimeCache(bytes, access, snapshot.generation, "request", cipher, key)
                } finally {
                    bytes.fill(0)
                }
            currentCoroutineContext().ensureActive()
            replicas.replace(ReplicaScope(access.sessionId), partition, previous, encoded)
        } finally {
            key.fill(0)
        }
    }
}
