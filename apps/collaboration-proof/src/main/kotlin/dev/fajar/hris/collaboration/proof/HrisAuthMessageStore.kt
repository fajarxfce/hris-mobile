package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationListAdmissionDataSource
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.*
import kotlin.time.Clock

/** Actual product outbox and use cases, restricted to the owned auth emulator fixture. */
internal class HrisAuthMessageStore(
    context: Context,
    private val runtime: AndroidCollaborationRuntimeLease,
    private val conversation: String,
    accessToken: String,
) {
    private val database = createAppDatabase(context)
    private val messageKey = AndroidCredentialStore(context, "auth-proof-message-key")
    private val receiptKey = AndroidCredentialStore(context, "auth-proof-receipt-key")
    private val listKey = AndroidCredentialStore(context, "auth-proof-list-key")
    private val observationKey =
        AndroidCredentialStore(context, "auth-proof-recipient-observation-key")
    private val access =
        runtime.owner.let {
            CollaborationLocalAccess(it.sessionId, it.audience, it.accountId, it.companyId)
        }
    private val messages =
        EncryptedCollaborationMessageRepository(
            database.commands,
            AesGcmContentCipher(),
            messageKey,
        )
    private val receipts =
        EncryptedCollaborationReceiptRepository(
            database.replicas,
            AesGcmContentCipher(),
            receiptKey,
        )
    private val lists =
        EncryptedCollaborationListRepository(
            database.replicas,
            AesGcmContentCipher(),
            listKey,
            MemoryCollaborationListAdmissionDataSource(),
        )
    private val app = HrisProofApplicationAccess(runtime.owner, accessToken)
    private val runtimes = HrisProofRuntimeAccess(runtime)

    suspend fun activate() {
        database.accounts.activate(
            access.sessionId,
            accountStorageKey(access.audience, access.accountId),
        )
    }

    suspend fun stage(id: String, text: String): CollaborationMessageRecord {
        val intent =
            CollaborationMessageIntent(
                id,
                access.messageOwner(),
                conversation,
                Clock.System.now(),
                text,
            )
        check(success(messages.insert(access, intent)) == CollaborationMessageInsertion.STORED)
        return success(
            StageCollaborationText(
                messages,
                lists,
                app.transport,
                runtimes,
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                EncryptedCollaborationRecipientObservationRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    observationKey,
                ),
            )(app.workspaceId, runtime.deviceId, id)
        )
    }

    suspend fun flush(): CollaborationDeliveryReport =
        success(
            DeliverCollaborationMessages(
                messages,
                receipts,
                lists,
                app.transport,
                runtimes,
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
                messages,
                EncryptedCollaborationRecipientObservationRepository(
                    database.replicas,
                    AesGcmContentCipher(),
                    observationKey,
                ),
            )(app.workspaceId, runtime.deviceId, conversation)
        )

    suspend fun read(id: String) = requireNotNull(success(messages.get(access, id)))

    suspend fun close() =
        closeHrisProofResources(
            null,
            listOf(
                { app.close() },
                { database.close() },
                { messageKey.close() },
                { observationKey.close() },
                { receiptKey.close() },
                { listKey.close() },
            ),
        )

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
