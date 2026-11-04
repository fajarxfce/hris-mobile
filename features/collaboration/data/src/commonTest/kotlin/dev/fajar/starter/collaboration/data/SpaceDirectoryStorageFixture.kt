package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.replica.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceDirectoryRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

class SpaceDirectoryDatabaseOwner(val open: () -> AppDatabase, val dispose: () -> Unit = {})

internal class SpaceDirectoryStorageFixture(private val owner: SpaceDirectoryDatabaseOwner) {
    var database = owner.open()
    val access =
        CollaborationSpaceLocalAccess(
            "local",
            "https://hris.example.test/",
            spaceDirectoryId(90000),
            spaceDirectoryId(90001),
        )
    val now = Instant.parse("2026-11-04T00:00:00Z")
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    val denied = Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
    var admissions = MemoryCollaborationSpaceDirectoryAdmissionSource()
    var repository = repository()

    fun repository(
        store: ReplicaStore = database.replicas,
        encryption: ContentCipher = cipher,
        pending: CollaborationSpaceDirectoryAdmissionSource = admissions,
    ) = EncryptedCollaborationSpaceDirectoryRepository(store, encryption, keys, pending)

    suspend fun activate(value: CollaborationSpaceLocalAccess = access) =
        database.accounts.activate(value.sessionId, directoryScope(value).accountKey)

    suspend fun checkpoint(value: CollaborationSpaceLocalAccess = access) =
        assertIs<AppResult.Success<CollaborationSpaceDirectoryCheckpoint?>>(
                repository.checkpoint(value)
            )
            .value

    suspend fun begin(generation: Int = 80000, value: CollaborationSpaceLocalAccess = access) {
        assertEquals(
            AppResult.Success(true),
            repository.begin(value, checkpoint(value), spaceDirectoryId(generation), now),
        )
    }

    fun remote(
        items: List<CollaborationSpaceContact>,
        more: Boolean = false,
        value: CollaborationSpaceLocalAccess = access,
    ) =
        CollaborationSpaceContactPage(
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    value.sessionId,
                    value.audience,
                    value.accountId,
                    spaceDirectoryId(90002),
                ),
                value.spaceId,
            ),
            items,
            if (more) items.last().accountId else null,
        )

    suspend fun append(
        items: List<CollaborationSpaceContact>,
        more: Boolean = false,
        value: CollaborationSpaceLocalAccess = access,
    ) {
        assertEquals(
            AppResult.Success(true),
            repository.append(
                value,
                requireNotNull(checkpoint(value)),
                remote(items, more, value),
                now,
            ),
        )
    }

    suspend fun seed(count: Int) {
        begin()
        if (count == 0) append(emptyList())
        else
            for (batch in (1..count).map(::spaceDirectoryContact).chunked(50)) append(
                batch,
                batch.last().accountId != spaceDirectoryId(count),
            )
    }

    suspend fun snapshot(value: CollaborationSpaceLocalAccess = access) =
        assertIs<AppResult.Success<CollaborationSpaceDirectorySnapshot>>(
                repository.observe(value).first()
            )
            .value

    suspend fun admission(value: CollaborationSpaceLocalAccess = access) =
        assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                repository.admission(value)
            )
            .value

    suspend fun raw(after: String? = null) =
        database.replicas
            .observe(directoryScope(access), directoryPartition(access), after, 200)
            .first()

    suspend fun reopen(value: CollaborationSpaceLocalAccess = access) {
        database.close()
        database = owner.open()
        admissions = MemoryCollaborationSpaceDirectoryAdmissionSource()
        repository = repository()
        activate(value)
    }

    fun close() {
        database.close()
        keys.close()
        owner.dispose()
    }
}

internal fun spaceDirectoryId(index: Int) =
    "b1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

internal fun spaceDirectoryContact(index: Int) =
    CollaborationSpaceContact(
        spaceDirectoryId(index),
        "Fictional employee $index",
        listOf(
            CollaborationSpaceContactCompany(
                spaceDirectoryId(91000 + index % 4),
                "Fictional company ${index % 4}",
            )
        ),
        false,
    )
