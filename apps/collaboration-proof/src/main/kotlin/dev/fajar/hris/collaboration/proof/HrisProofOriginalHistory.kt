package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Actual product snapshot, decoder, encrypted storage and reopened local database. */
internal class HrisProofOriginalHistory(
    private val replicas: () -> ReplicaStore,
    private val credentials: AtomicCredentialStore,
    private val access: CollaborationLocalAccess,
    private val conversationId: String,
    private val roomId: String,
) {
    suspend fun verify(
        window: AndroidCollaborationHistoryWindow,
        expected: List<Triple<String, String, String>>,
        reopen: suspend () -> Unit,
    ): JSONObject {
        val originals = window.originals.filterIsInstance<CollaborationHistoryChange.Text>()
        check(originals.size == 2 && expected.size == 2)
        for ((eventId, sender, text) in expected) {
            val original = originals.single { it.eventId == eventId }
            check(original.sender == sender && original.text == text && original.logicalId != null)
        }
        check(success(repository().merge(access, conversationId, roomId, originals)))
        val before = success(repository().observe(access, conversationId).first())
        check(before.messages.size == 2 && before.next == null)
        reopen()
        check(success(repository().observe(access, conversationId).first()) == before)
        for (original in originals) {
            val byEvent =
                success(repository().getByEvent(access, conversationId, roomId, original.eventId))
            check(byEvent?.text == original.text && byEvent.contentHash == original.contentHash)
            check(
                success(
                    repository()
                        .getByLogicalId(
                            access,
                            conversationId,
                            roomId,
                            original.sender,
                            requireNotNull(original.logicalId),
                        )
                ) == byEvent
            )
        }
        check(success(repository().merge(access, conversationId, roomId, originals)))
        check(success(repository().observe(access, conversationId).first()) == before)
        return JSONObject()
            .put("originals", originals.size)
            .put("unavailable", window.unavailableEventIds.size)
            .put("extractionNanos", window.extractionNanos)
            .put("modelCharacters", window.modelCharacters)
            .put("originalCharacters", window.originalCharacters)
    }

    private fun repository() =
        EncryptedCollaborationHistoryRepository(replicas(), AesGcmContentCipher(), credentials)

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
