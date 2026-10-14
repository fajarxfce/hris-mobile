package dev.fajar.starter.people.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.replica.datasources.MemoryDirectoryReadAdmissionDataSource
import dev.fajar.starter.people.data.replica.mappers.directoryReplicaScope
import dev.fajar.starter.people.data.replica.repositories.EncryptedDirectoryReplicaRepository
import dev.fajar.starter.people.domain.entities.DirectoryEmployee
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.policies.initialDirectoryCheckpoint
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

class DirectoryReplicaFixture(private val openDatabase: () -> AppDatabase) {
    var db = openDatabase()
    val now = Instant.parse("2026-10-14T00:00:00Z")
    val access =
        DirectoryAccess(
            "session",
            directoryFixtureId(9001),
            "https://hris.example.test/",
            directoryFixtureId(9002),
            "Asia/Jakarta",
            setOf("people.directory.read"),
        )
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var admissions = MemoryDirectoryReadAdmissionDataSource()
    var repository = EncryptedDirectoryReplicaRepository(db.replicas, cipher, keys, admissions)
    val record = directoryFixtureRecord(1)

    suspend fun activate(owner: DirectoryAccess = access) =
        db.accounts.activate(owner.sessionId, directoryReplicaScope(owner).accountKey)

    suspend fun checkpoint(owner: DirectoryAccess = access) =
        assertIs<AppResult.Success<DirectoryCheckpoint?>>(repository.checkpoint(owner)).value

    suspend fun publish(records: List<DirectoryRecord>, publish: Boolean = true) {
        var checkpoint = checkpoint()
        if (checkpoint == null) {
            checkpoint = initialDirectoryCheckpoint(access, "generation", now)
            assertEquals(AppResult.Success(true), repository.begin(checkpoint, null))
        }
        for ((index, batch) in records.chunked(50).ifEmpty { listOf(emptyList()) }.withIndex()) {
            val previous = requireNotNull(checkpoint)
            val visible = previous.published || (publish && (index + 1) * 50 >= records.size)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = DirectorySyncPhase.CHANGES,
                    cursor = "head",
                    published = visible,
                    refreshedAt = if (visible) now else null,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(
                    previous,
                    next,
                    batch.map {
                        DirectoryReplicaEntry(
                            SyncReference(
                                SyncCollection.DIRECTORY_EMPLOYEES,
                                it.employee.id,
                                it.version,
                            ),
                            it,
                        )
                    },
                ),
            )
            checkpoint = next
        }
    }

    suspend fun snapshot(owner: DirectoryAccess = access) =
        assertIs<AppResult.Success<DirectorySnapshot>>(repository.observe(owner).first()).value

    suspend fun reopen(owner: DirectoryAccess = access) {
        db.close()
        db = openDatabase()
        admissions = MemoryDirectoryReadAdmissionDataSource()
        repository = EncryptedDirectoryReplicaRepository(db.replicas, cipher, keys, admissions)
        activate(owner)
    }

    fun close() {
        db.close()
        keys.close()
    }
}

fun directoryFixtureId(index: Int) = "91000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

fun directoryFixtureRecord(index: Int, version: Long = 0) =
    DirectoryRecord(
        version,
        DirectoryEmployee(
            directoryFixtureId(index),
            "E${index.toString().padStart(5, '0')}",
            "Colleague $index",
            null,
            if (index % 2 == 0) "Engineering" else "People",
            null,
            directoryFixtureId(if (index % 2 == 0) 990 else 991),
        ),
    )
