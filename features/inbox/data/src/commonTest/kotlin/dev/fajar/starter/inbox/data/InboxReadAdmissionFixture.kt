package dev.fajar.starter.inbox.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.inbox.data.datasources.MemoryInboxReadAdmissionDataSource
import dev.fajar.starter.inbox.data.mappers.inboxReplicaScope
import dev.fajar.starter.inbox.data.repositories.EncryptedInboxCacheRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

class InboxReadAdmissionFixture(val db: AppDatabase) {
    val now = Instant.parse("2026-10-11T00:00:00Z")
    val access =
        InboxAccess(
            "session",
            "account",
            "https://hris.example.test/",
            "company",
            setOf("announcements.read"),
            now + 24.hours,
            now + 1.hours,
        )
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    val admissions = MemoryInboxReadAdmissionDataSource()
    val repository = EncryptedInboxCacheRepository(db.replicas, cipher, keys, admissions)
    val item =
        InboxItem(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
            "33333333-3333-3333-3333-333333333333",
            1,
            "Private notice",
            "Private body",
            true,
            now,
            null,
            null,
        )

    suspend fun activate() =
        db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)

    suspend fun publish() {
        val initial =
            InboxCheckpoint(
                access,
                "generation",
                0,
                InboxSyncPhase.BOOTSTRAP,
                null,
                false,
                null,
                now + 1.hours,
                null,
            )
        assertEquals(AppResult.Success(true), repository.begin(initial, null))
        assertEquals(
            AppResult.Success(true),
            repository.commit(
                initial,
                initial.copy(
                    revision = 1,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "cursor",
                    published = true,
                    refreshedAt = now,
                ),
                listOf(InboxReplicaEntry(item.id, item.version, item)),
            ),
        )
    }

    suspend fun page() = repository.observe(access, InboxCacheQuery.Page()).first()

    fun close() {
        db.close()
        keys.close()
    }
}
