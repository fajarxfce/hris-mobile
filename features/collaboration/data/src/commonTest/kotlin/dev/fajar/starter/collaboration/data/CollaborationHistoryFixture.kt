package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationHistoryRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlinx.coroutines.flow.first

class CollaborationHistoryDatabaseOwner(val open: () -> AppDatabase, val dispose: () -> Unit = {})

internal class CollaborationHistoryFixture(private val owner: CollaborationHistoryDatabaseOwner) {
    var database = owner.open()
    val access =
        CollaborationLocalAccess(
            "login",
            "https://hris.example.test/",
            historyId(9000),
            historyId(9001),
        )
    val conversation = historyId(9002)
    val room = "!history:example.test"
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var repository = repository()

    fun repository(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationHistoryRepository(store, cipher, keys)

    fun event(index: Int = 1) =
        CollaborationHistoryChange.Text(
            "\$event-$index",
            "@fictional:example.test",
            historyId(index),
            1_793_491_200_000L + index,
            " Original fictional message $index\n",
            index.toString(16).padStart(64, '0'),
        )

    suspend fun activate(owner: CollaborationLocalAccess = access) =
        database.accounts.activate(owner.sessionId, historyScope(owner).accountKey)

    suspend fun merge(vararg changes: CollaborationHistoryChange) {
        assertEquals(
            AppResult.Success(true),
            repository.merge(access, conversation, room, changes.toList()),
        )
    }

    suspend fun read(
        owner: CollaborationLocalAccess = access,
        after: String? = null,
        limit: Int = 50,
        before: String? = null,
        overlap: Int = 0,
    ) =
        assertIs<AppResult.Success<CollaborationHistoryPage>>(
                repository.observe(owner, conversation, room, after, limit, before, overlap).first()
            )
            .value

    suspend fun lookup(event: String = event().eventId) =
        assertIs<AppResult.Success<CollaborationHistoryMessage?>>(
                repository.getByEvent(access, conversation, room, event)
            )
            .value

    suspend fun raw() =
        database.replicas
            .observe(historyScope(access), historyPartition(access, conversation), limit = 200)
            .first()

    suspend fun reopen(owner: CollaborationLocalAccess = access) {
        database.close()
        database = this.owner.open()
        repository = repository()
        activate(owner)
    }

    fun close() {
        database.close()
        keys.close()
        owner.dispose()
    }
}

internal fun historyId(index: Int) = "a1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
