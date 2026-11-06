package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecordCodec
import dev.fajar.starter.collaboration.domain.entities.CollaborationSdkCredentialInventory

internal fun CollaborationSdkRecord.toCredentialInventory(codec: CollaborationSdkRecordCodec) =
    CollaborationSdkCredentialInventory(
        matrixUserId,
        matrixDeviceId,
        bootstrapOperationId,
        messageOperationId,
        credentialRevision,
        initialized,
        codec.handoverIntent(this),
    )
