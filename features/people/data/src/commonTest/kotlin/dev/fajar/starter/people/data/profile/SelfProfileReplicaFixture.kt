package dev.fajar.starter.people.data.profile

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.profile.datasources.MemorySelfProfileReadAdmissionDataSource
import dev.fajar.starter.people.data.profile.mappers.selfProfileReplicaScope
import dev.fajar.starter.people.data.profile.repositories.EncryptedSelfProfileReplicaRepository
import dev.fajar.starter.people.domain.entities.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.initialSelfProfileCheckpoint
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

class SelfProfileReplicaFixture(private val openDatabase: () -> AppDatabase) {
    var db = openDatabase()
    val now = Instant.parse("2026-10-14T00:00:00Z")
    val access =
        SelfProfileAccess(
            "session",
            selfProfileFixtureId(9001),
            "https://hris.example.test/",
            selfProfileFixtureId(9002),
            "Asia/Jakarta",
            setOf("people.self.read"),
        )
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var admissions = MemorySelfProfileReadAdmissionDataSource()
    var repository = EncryptedSelfProfileReplicaRepository(db.replicas, cipher, keys, admissions)
    val record = selfProfileFixtureRecord(1)

    suspend fun activate(owner: SelfProfileAccess = access) =
        db.accounts.activate(owner.sessionId, selfProfileReplicaScope(owner).accountKey)

    suspend fun checkpoint(owner: SelfProfileAccess = access) =
        assertIs<AppResult.Success<SelfProfileCheckpoint?>>(repository.checkpoint(owner)).value

    suspend fun publish(records: List<SelfProfileRecord>, publish: Boolean = true) {
        var checkpoint = checkpoint()
        if (checkpoint == null) {
            checkpoint = initialSelfProfileCheckpoint(access, "generation", now)
            assertEquals(AppResult.Success(true), repository.begin(checkpoint, null))
        }
        for ((index, batch) in records.chunked(50).ifEmpty { listOf(emptyList()) }.withIndex()) {
            val previous = requireNotNull(checkpoint)
            val visible = previous.published || (publish && (index + 1) * 50 >= records.size)
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    phase = SelfProfileSyncPhase.CHANGES,
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
                        SelfProfileReplicaEntry(
                            SyncReference(SyncCollection.SELF_PROFILES, it.id, it.version),
                            it,
                        )
                    },
                ),
            )
            checkpoint = next
        }
    }

    suspend fun snapshot(owner: SelfProfileAccess = access) =
        assertIs<AppResult.Success<SelfProfileSnapshot>>(repository.observe(owner).first()).value

    suspend fun reopen(owner: SelfProfileAccess = access) {
        db.close()
        db = openDatabase()
        admissions = MemorySelfProfileReadAdmissionDataSource()
        repository = EncryptedSelfProfileReplicaRepository(db.replicas, cipher, keys, admissions)
        activate(owner)
    }

    fun close() {
        db.close()
        keys.close()
    }
}

fun selfProfileFixtureId(index: Int) =
    "91000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

fun selfProfileFixtureRecord(index: Int, version: Long = 0): SelfProfileRecord {
    val employee =
        Employment(
            selfProfileFixtureId(index),
            selfProfileFixtureId(9002),
            selfProfileFixtureId(9003),
            selfProfileFixtureId(9001),
            "E${index.toString().padStart(5, '0')}",
            "Private employee",
            "private@example.test",
            EmploymentContract.Permanent,
            EmploymentStatus.Active,
            LocalDate(2024, 1, 1),
            null,
            4,
            2,
            LocalDate(2025, 1, 1),
        )
    val profile =
        PersonProfile(
            selfProfileFixtureId(9003),
            selfProfileFixtureId(9002),
            selfProfileFixtureId(9001),
            "Private employee",
            LocalDate(1990, 2, 3),
            "ID",
            "private@example.test",
            7,
        )
    return SelfProfileRecord(
        selfProfileFixtureId(index),
        version,
        "Asia/Jakarta",
        EmploymentProfile(
            EmploymentDetails(LocalDate(2026, 10, 14), employee, null, null, null, null, null),
            profile,
        ),
    )
}
