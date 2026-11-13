package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SPACE_INBOX_REPAIRS = "original-repairs"

/** A completed encrypted marker retains proof without keeping the original message text. */
@Serializable
internal data class InboxRepairDto(
    val id: String,
    val insertedAtRevision: Long,
    val digest: String,
    val intent: SpaceMessageIntentDto?,
) {
    fun reference(access: CollaborationSpaceLocalAccess): CollaborationSpaceInboxRepairReference? =
        intent?.let {
            CollaborationSpaceInboxRepairReference(
                    it.toSpaceMessageIntent(access),
                    insertedAtRevision,
                )
                .also { reference ->
                    require(
                        reference.intent.id == id &&
                            validCollaborationSpaceInboxRepairReference(reference)
                    )
                }
        }

    override fun toString() = "InboxRepairDto([redacted])"
}

/** Plaintext mapping and proof validation. The inbox codec owns ciphertext and coordinates. */
internal class CollaborationSpaceInboxRepairCodec(
    private val access: CollaborationSpaceLocalAccess
) {
    suspend fun proposed(
        change: CollaborationSpaceInboxRepairChange,
        previous: InboxRepairDto?,
        revision: Long,
    ): InboxRepairDto {
        val source =
            when (change) {
                is CollaborationSpaceInboxRepairChange.Retain -> change.intent
                is CollaborationSpaceInboxRepairChange.Complete -> change.reference.intent
            }
        require(validCollaborationSpaceMessageIntent(access, source) && source.editTarget == null)
        val intent = source.toSpaceMessageDto(access)
        val digest = digest(intent)
        if (previous != null) require(previous.id == source.id && previous.digest == digest)
        return when (change) {
            is CollaborationSpaceInboxRepairChange.Retain ->
                previous ?: InboxRepairDto(source.id, revision, digest, intent)
            is CollaborationSpaceInboxRepairChange.Complete -> {
                requireNotNull(previous)
                require(previous.insertedAtRevision == change.reference.insertedAtRevision)
                require(previous.intent == null || previous.intent == intent)
                previous.copy(intent = null)
            }
        }
    }

    suspend fun validate(value: InboxRepairDto, conversationId: String) {
        require(
            validCollaborationMessageId(value.id) &&
                value.insertedAtRevision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
        )
        require(value.digest.matches(Regex("[0-9a-f]{64}")))
        value.intent?.let {
            val intent = it.toSpaceMessageIntent(access)
            require(
                intent.editTarget == null &&
                    intent.id == value.id &&
                    intent.conversationId == conversationId
            )
            require(value.digest == digest(it))
        }
    }

    private suspend fun digest(value: SpaceMessageIntentDto): String {
        val bytes = Json.encodeToString(value).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            CryptographyProvider.Default.get(SHA256).hasher().hash(bytes).toHexString()
        } finally {
            bytes.fill(0)
        }
    }
}
