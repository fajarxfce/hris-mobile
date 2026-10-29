package dev.fajar.starter.identity.data.firebase.dto

/** Installed public client configuration; excluded from diagnostics together with credentials. */
data class FirebaseClientOptions(
    val projectId: String,
    val apiKey: String,
    val googleWebClientId: String? = null,
    val androidPackage: String? = null,
    val androidCertificate: String? = null,
    val iosBundleId: String? = null,
) {
    override fun toString() = "FirebaseClientOptions(<redacted>)"
}
