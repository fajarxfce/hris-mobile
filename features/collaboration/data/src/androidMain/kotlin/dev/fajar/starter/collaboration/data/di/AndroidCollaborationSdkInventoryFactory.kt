package dev.fajar.starter.collaboration.data.di

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.FileCollaborationSdkInventorySource
import dev.fajar.starter.collaboration.data.repositories.StoredCollaborationSdkInventoryRepository
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AndroidCredentialStore
import java.io.File

/** Application-owned configuration; inspection acquires and closes its own raw resources. */
fun androidCollaborationSdkInventoryRepository(
    context: Context,
    server: IdentityServer,
    endpoint: CollaborationSdkEndpoint,
): StoredCollaborationSdkInventoryRepository {
    val application = context.applicationContext
    return StoredCollaborationSdkInventoryRepository(
        FileCollaborationSdkInventorySource(File(application.noBackupFilesDir, "collaboration")),
        { AndroidCredentialStore(application, it) },
        server,
        endpoint,
    )
}
