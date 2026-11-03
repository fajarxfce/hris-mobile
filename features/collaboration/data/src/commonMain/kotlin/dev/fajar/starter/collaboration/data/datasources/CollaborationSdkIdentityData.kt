package dev.fajar.starter.collaboration.data.datasources

/** Raw public identity from the opened SDK crypto store; no private key leaves the SDK. */
data class CollaborationSdkIdentityData(
    val userId: String,
    val deviceId: String,
    val ed25519: String?,
    val curve25519: String?,
) {
    override fun toString() = "CollaborationSdkIdentityData([redacted])"
}
