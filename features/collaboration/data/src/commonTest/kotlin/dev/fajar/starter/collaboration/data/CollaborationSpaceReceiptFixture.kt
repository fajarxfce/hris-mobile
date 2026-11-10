package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceReceiptRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

internal class CollaborationSpaceReceiptFixture(
    private val owner: CollaborationReceiptDatabaseOwner
) {
    var database = owner.open()
    val access =
        CollaborationSpaceLocalAccess(
            "login",
            "https://hris.example.test/",
            receiptId(9000),
            receiptId(9200),
        )
    val conversation = receiptId(9002)
    val legacyAccess =
        CollaborationLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            receiptId(9300),
        )
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var repository = repository()

    fun repository(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceReceiptRepository(store, cipher, keys)

    suspend fun activate(owner: CollaborationSpaceLocalAccess = access) =
        database.accounts.activate(owner.sessionId, spaceReceiptScope(owner).accountKey)

    fun receipt(index: Int = 1) =
        CollaborationSpaceMessageReceipt(
            CollaborationSpaceMessageIntent(
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
            CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64)),
            listOf("\$event-$index"),
        )

    fun legacyReceipt() =
        receipt().let {
            CollaborationMessageReceipt(
                CollaborationMessageIntent(
                    it.intent.id,
                    legacyAccess.messageOwner(),
                    conversation,
                    it.intent.createdAt,
                    "Legacy original",
                ),
                it.device,
                it.transactionId,
                it.eventIds,
            )
        }

    suspend fun append(vararg receipts: CollaborationSpaceMessageReceipt) {
        assertEquals(
            AppResult.Success(true),
            repository.append(access, conversation, receipts.toList()),
        )
    }

    suspend fun read(owner: CollaborationSpaceLocalAccess = access) =
        assertIs<AppResult.Success<List<CollaborationSpaceMessageReceipt>>>(
                repository.read(owner, conversation)
            )
            .value

    suspend fun raw() =
        database.replicas
            .observe(
                spaceReceiptScope(access),
                spaceReceiptPartition(access, conversation),
                limit = 129,
            )
            .first()

    suspend fun reopen(owner: CollaborationSpaceLocalAccess = access) {
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
