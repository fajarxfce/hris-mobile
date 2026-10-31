package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.data.mappers.collaborationId
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.HttpClientSettings
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.http.Url
import kotlinx.serialization.json.Json

/** Explicit application configuration. Matrix identities and restored records cannot select it. */
class CollaborationSdkEndpoint(baseUrl: String, allowCleartext: Boolean = false) {
    val baseUrl: String

    init {
        HttpClientSettings(baseUrl, allowCleartext = allowCleartext)
        val parsed = Url(baseUrl)
        require(parsed.host.isNotBlank() && parsed.encodedPath in setOf("", "/"))
        this.baseUrl = parsed.toString().trimEnd('/') + "/"
    }
}

internal data class CollaborationSdkScope(
    val owner: CollaborationOwner,
    val deviceId: String,
    val homeserverUrl: String,
    val storeId: String,
) {
    // The protected record validates the complete 256-bit store identity before any update.
    val credentialRecordName
        get() = "matrix_" + storeId.take(56)

    override fun toString() = "CollaborationSdkScope([redacted])"
}

internal suspend fun collaborationSdkScope(
    owner: CollaborationOwner,
    deviceId: String,
    server: IdentityServer,
    endpoint: CollaborationSdkEndpoint,
): CollaborationSdkScope {
    require(owner.audience == server.audience)
    listOf(owner.accountId, owner.companyId, owner.nativeSessionId, deviceId)
        .forEach(::collaborationId)
    val bytes =
        Json.encodeToString(
                listOf(
                    "hris-matrix-store-v1",
                    owner.audience,
                    owner.accountId,
                    owner.companyId,
                    owner.nativeSessionId,
                    deviceId,
                    endpoint.baseUrl,
                )
            )
            .encodeToByteArray()
    require(bytes.size <= 4096)
    val id = CryptographyProvider.Default.get(SHA256).hasher().hash(bytes).toHexString()
    return CollaborationSdkScope(owner, deviceId, endpoint.baseUrl, id)
}
