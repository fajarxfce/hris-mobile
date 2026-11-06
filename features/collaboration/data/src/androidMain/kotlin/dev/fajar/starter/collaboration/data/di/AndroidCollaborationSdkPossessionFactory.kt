package dev.fajar.starter.collaboration.data.di

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkPrivateIdentitySource
import dev.fajar.starter.collaboration.data.datasources.FileCollaborationSdkInventorySource
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationSdkPossessionRepository
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AndroidCredentialStore
import java.io.File

fun androidCollaborationSdkPossessionRepository(
    context: Context,
    server: IdentityServer,
    endpoint: CollaborationSdkEndpoint,
): StoredCollaborationSdkPossessionRepository {
    val application = context.applicationContext
    return StoredCollaborationSdkPossessionRepository(
        FileCollaborationSdkInventorySource(File(application.noBackupFilesDir, "collaboration")),
        AndroidCollaborationSdkPrivateIdentitySource(),
        { AndroidCredentialStore(application, it) },
        server,
        endpoint,
    )
}
