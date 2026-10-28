package dev.fajar.starter.identity.data.firebase.dto

import kotlinx.serialization.Serializable

@Serializable
data class NativeFirebaseConfigurationDto(
    val enabled: Boolean,
    val projectId: String?,
    val providers: List<String>,
)

@Serializable
data class NativeFirebaseLoginDto(val idToken: String, val deviceName: String) {
    override fun toString() = "NativeFirebaseLoginDto(<redacted>)"
}
