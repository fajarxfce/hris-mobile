package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationReceiptRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

class CollaborationReceiptDatabaseOwner(val open: () -> AppDatabase, val dispose: () -> Unit = {})

internal class CollaborationReceiptFixture(private val owner: CollaborationReceiptDatabaseOwner) {
    var database = owner.open()
    val access =
        CollaborationLocalAccess(
            "login",
            "https://hris.example.test/",
            receiptId(9000),
            receiptId(9001),
        )
    val conversation = receiptId(9002)
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var repository = repository()

    fun repository(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationReceiptRepository(store, cipher, keys)

    suspend fun activate(owner: CollaborationLocalAccess = access) =
        database.accounts.activate(owner.sessionId, receiptScope(owner).accountKey)

    fun receipt(index: Int = 1) =
        CollaborationMessageReceipt(
            CollaborationMessageIntent(
                receiptId(index),
                access.messageOwner(),
                conversation,
                Instant.parse("2026-11-01T00:00:00Z"),
                " Original fictional message $index\n",
            ),
            CollaborationMessageDevice(
                receiptId(9100),
                receiptId(9101),
                receiptId(9102),
                "!fictional:example.test",
            ),
            "txn-$index",
            listOf("\$event-$index"),
        )

    suspend fun append(vararg receipts: CollaborationMessageReceipt) {
        assertEquals(
            AppResult.Success(true),
            repository.append(access, conversation, receipts.toList()),
        )
    }

    suspend fun read(owner: CollaborationLocalAccess = access) =
        assertIs<AppResult.Success<List<CollaborationMessageReceipt>>>(
                repository.read(owner, conversation)
            )
            .value

    suspend fun raw() =
        database.replicas
            .observe(receiptScope(access), receiptPartition(access, conversation), limit = 129)
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

internal fun receiptId(index: Int) = "c1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
