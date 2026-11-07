package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeLease

/** Exposes logical ownership without exposing legacy operations or a raw native client. */
internal class AndroidCollaborationSpaceRuntimeLease(
    identity: SdkSpaceRuntimeIdentity,
    private val resource: AndroidCollaborationRuntimeLease,
) : CollaborationSpaceRuntimeLease {
    override val owner = identity.owner
    override val deviceId = identity.deviceId
    override val installedOperationId = identity.operationId
    override val credentialFailures = resource.credentialFailures

    override fun publish() = resource.publish()

    override suspend fun retryCredentialWrite() = resource.retryCredentialWrite()

    override suspend fun close() = resource.close()
}
