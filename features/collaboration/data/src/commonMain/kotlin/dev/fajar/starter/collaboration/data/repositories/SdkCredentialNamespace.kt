package dev.fajar.starter.collaboration.data.repositories

/** Fixed when a private-store lease is opened; an SDK callback cannot select a successor. */
internal sealed interface SdkCredentialNamespace {
    data object Original : SdkCredentialNamespace

    data class Adopted(val operationId: String) : SdkCredentialNamespace {
        override fun toString() = "Adopted([redacted])"
    }
}

/** Only the admitted lease's credentials cross the native callback boundary. */
internal data class SdkSessionCredentials(
    val matrixUserId: String,
    val matrixDeviceId: String,
    val homeserverUrl: String,
    val accessToken: String,
    val refreshToken: String,
) {
    override fun toString() = "SdkSessionCredentials([redacted])"
}
