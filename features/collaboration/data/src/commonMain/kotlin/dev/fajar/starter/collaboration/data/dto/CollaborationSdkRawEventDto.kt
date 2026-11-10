package dev.fajar.starter.collaboration.data.dto

/** SDK facts only. Decryption is distinct from sender trust and current content admission. */
internal enum class CollaborationSdkRawEventEncryption {
    NOT_DECRYPTED,
    DECRYPTED,
    UNABLE_TO_DECRYPT,
}

/**
 * A detached point read. The mapper must verify the requested identity and classify its content.
 */
internal data class CollaborationSdkRawEventDto(
    val roomId: String,
    val eventId: String,
    val roomJoined: Boolean,
    val roomEncrypted: Boolean,
    val encryption: CollaborationSdkRawEventEncryption,
    val json: String,
) {
    override fun toString() = "CollaborationSdkRawEventDto([redacted])"
}
