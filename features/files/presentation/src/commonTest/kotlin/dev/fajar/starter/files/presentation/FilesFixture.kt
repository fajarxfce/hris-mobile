package dev.fajar.starter.files.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.entities.*
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.repositories.*
import kotlinx.coroutines.flow.*

internal val testFile = TransferFile("sample.txt", "text/plain", 8)

internal fun testTransfer(direction: TransferDirection = TransferDirection.Download) =
    Transfer("transfer", direction, testFile, 1, "file", "\"v1\"", status = TransferStatus.Queued)

internal class TestSessions : SessionRepository {
    val value =
        MutableStateFlow<Session?>(
            Session(
                "one",
                User("u", "User", "u@example.com"),
                SessionTokens("a", "r", Long.MAX_VALUE),
            )
        )
    var collectors = 0
    override val persistent = true

    override fun observe() = flow {
        collectors++
        try {
            emitAll(value.map { AppResult.Success(it) })
        } finally {
            collectors--
        }
    }

    override suspend fun current() = AppResult.Success(value.value)

    override suspend fun compareAndSet(expected: Session?, updated: Session?) =
        AppResult.Success(value.compareAndSet(expected, updated))
}

internal class TestAccess : AccessRepository {
    var permissions = Permission.entries.toSet()

    override fun observe(sessionId: String) = flow { emit(cached(sessionId)) }

    override suspend fun cached(sessionId: String) =
        AppResult.Success(AccessSnapshot(sessionId, setOf("editor"), permissions, Long.MAX_VALUE))

    override suspend fun refresh(sessionId: String) = AppResult.Success(Unit)

    override suspend fun invalidate(sessionId: String) = AppResult.Success(Unit)
}

internal class TestQueue : TransferQueueRepository {
    var owner = "one"
    val rows = MutableStateFlow<List<Transfer>>(emptyList())
    val chunks = mutableMapOf<String, MutableMap<Long, ByteArray>>()
    var collectors = 0

    override fun observe(sessionId: String) = flow {
        collectors++
        try {
            emitAll(rows.map { AppResult.Success(if (sessionId == owner) it else emptyList()) })
        } finally {
            collectors--
        }
    }

    override suspend fun get(sessionId: String, id: String) =
        AppResult.Success(rows.value.firstOrNull { it.id == id && sessionId == owner })

    override suspend fun create(
        sessionId: String,
        transfer: Transfer,
        maxBytes: Long,
        maxItems: Int,
    ): AppResult<Boolean> {
        if (
            sessionId != owner ||
                rows.value.size >= maxItems ||
                rows.value.sumOf { it.file.size } + transfer.file.size > maxBytes
        )
            return AppResult.Success(false)
        rows.value += transfer
        return AppResult.Success(true)
    }

    override suspend fun commit(
        sessionId: String,
        transfer: Transfer,
        chunk: ByteArray?,
        clearContent: Boolean,
    ): AppResult<Transfer?> {
        val old = rows.value.firstOrNull { it.id == transfer.id }
        if (sessionId != owner || old?.checkpointVersion != transfer.checkpointVersion)
            return AppResult.Success(null)
        if (chunk != null)
            chunks.getOrPut(transfer.id) { mutableMapOf() }[transfer.storedBytes] = chunk
        if (clearContent) chunks.remove(transfer.id)
        val updated =
            transfer.copy(
                checkpointVersion = transfer.checkpointVersion + 1,
                storedBytes = if (clearContent) 0 else transfer.storedBytes + (chunk?.size ?: 0),
            )
        rows.value = rows.value.map { if (it.id == transfer.id) updated else it }
        return AppResult.Success(updated)
    }

    override suspend fun read(sessionId: String, id: String, offset: Long): AppResult<ByteArray> {
        val block = chunks.getValue(id).entries.filter { it.key <= offset }.maxBy { it.key }
        return AppResult.Success(
            block.value.copyOfRange((offset - block.key).toInt(), block.value.size)
        )
    }

    override suspend fun remove(sessionId: String, id: String, version: Long): AppResult<Boolean> {
        if (
            sessionId != owner ||
                rows.value.firstOrNull { it.id == id }?.checkpointVersion != version
        )
            return AppResult.Success(false)
        rows.value = rows.value.filterNot { it.id == id }
        chunks.remove(id)
        return AppResult.Success(true)
    }
}

internal class TestGateway : TransferGatewayRepository {
    var serverOffset = 0L
    val sentOffsets = mutableListOf<Long>()
    var onDownload: suspend (Transfer) -> AppResult<ByteArray> = {
        AppResult.Success(ByteArray((it.file.size - it.offset).toInt()) { 1 })
    }
    var onUpload: suspend (Transfer, ByteArray) -> AppResult<Long> = { transfer, bytes ->
        serverOffset = transfer.offset + bytes.size
        AppResult.Success(serverOffset)
    }

    override suspend fun describe(sessionId: String, resourceId: String) =
        AppResult.Success(RemoteFile(resourceId, testFile, "\"v1\""))

    override suspend fun openUpload(sessionId: String, transfer: Transfer) =
        AppResult.Success(UploadCheckpoint("file", serverOffset))

    override suspend fun download(sessionId: String, transfer: Transfer, maxBytes: Int) =
        onDownload(transfer)

    override suspend fun upload(
        sessionId: String,
        transfer: Transfer,
        bytes: ByteArray,
    ): AppResult<Long> {
        sentOffsets += transfer.offset
        return onUpload(transfer, bytes)
    }
}

internal class TestScheduler : SyncScheduleRepository {
    var requests = 0

    override suspend fun request(key: String): AppResult<Unit> {
        requests++
        return AppResult.Success(Unit)
    }
}
