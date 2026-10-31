package dev.fajar.starter.collaboration.data.di

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkClientSource
import dev.fajar.starter.collaboration.data.datasources.FileCollaborationSdkStoreSource
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AesGcmContentCipher
import dev.fajar.starter.securestorage.AndroidCredentialStore
import java.io.File

/** Application composition owns this repository once per process, never an Activity. */
fun androidCollaborationRuntimeRepository(
    context: Context,
    server: IdentityServer,
    endpoint: CollaborationSdkEndpoint,
): AndroidCollaborationRuntimeRepository {
    val application = context.applicationContext
    return AndroidCollaborationRuntimeRepository(
        stores =
            FileCollaborationSdkStoreSource(File(application.noBackupFilesDir, "collaboration")),
        credentialFactory = { AndroidCredentialStore(application, it) },
        clients = AndroidCollaborationSdkClientSource(),
        cipher = AesGcmContentCipher(),
        server = server,
        endpoint = endpoint,
    )
}
