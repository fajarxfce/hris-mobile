package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.RawEventEncryption

/** One invocation owns its room handle and detached result; the parent owns the supplied client. */
internal class AndroidCollaborationRawEventSource(private val client: Client) :
    CollaborationRawEventSource {
    override suspend fun read(roomId: String, eventId: String): CollaborationSdkRawEventDto {
        currentCoroutineContext().ensureActive()
        return checkNotNull(client.getRoom(roomId)).use { room ->
            currentCoroutineContext().ensureActive()
            val joined = room.membership() == Membership.JOINED
            val encrypted = room.isEncrypted()
            currentCoroutineContext().ensureActive()
            val raw = room.loadOrFetchRawEvent(eventId)
            try {
                currentCoroutineContext().ensureActive()
                val json = raw.json
                require(json.length in 1..96_000)
                val bytes = json.encodeToByteArray(throwOnInvalidSequence = true)
                try {
                    require(bytes.size <= 96_000)
                } finally {
                    bytes.fill(0)
                }
                val encryption =
                    when (raw.encryption) {
                        RawEventEncryption.NOT_DECRYPTED ->
                            CollaborationSdkRawEventEncryption.NOT_DECRYPTED
                        RawEventEncryption.DECRYPTED -> CollaborationSdkRawEventEncryption.DECRYPTED
                        RawEventEncryption.UNABLE_TO_DECRYPT ->
                            CollaborationSdkRawEventEncryption.UNABLE_TO_DECRYPT
                    }
                CollaborationSdkRawEventDto(roomId, eventId, joined, encrypted, encryption, json)
            } finally {
                raw.json = ""
            }
        }
    }
}
