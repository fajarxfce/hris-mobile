package dev.fajar.starter.app.di

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSdkInventoryRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSdkPossessionRepository
import dev.fajar.starter.collaboration.domain.usecases.ActivateCollaborationDevice
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.push.usecases.ObserveNativePushStatus
import dev.fajar.starter.securestorage.AtomicCredentialStore
import io.ktor.client.HttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The application host supplies platform resources; feature bindings remain in collaboration. */
internal fun applicationCollaborationModule(
    runtime: (IdentityServer, HttpClient) -> CollaborationRuntimeRepository,
    sdkInventory: (IdentityServer) -> CollaborationSdkInventoryRepository,
    sdkPossession: (IdentityServer) -> CollaborationSdkPossessionRepository,
    listCredentials: AtomicCredentialStore,
    spaceDirectoryCredentials: AtomicCredentialStore,
    receiptCredentials: AtomicCredentialStore,
    historyCredentials: AtomicCredentialStore,
    notificationInputCredentials: AtomicCredentialStore,
) = module {
    single<CollaborationSdkInventoryRepository> { sdkInventory(get()) }
    single<CollaborationSdkPossessionRepository> { sdkPossession(get()) }
    single<CollaborationRuntimeRepository> {
        runtime(get(), get(named(dev.fajar.starter.network.HttpClients.Public)))
    }
    single {
            val push = inject<ObserveNativePushStatus>()
            val activate = inject<ActivateCollaborationDevice>()
            ApplicationCollaborationLifetime(
                get(),
                get(),
                get(),
                get(),
                { push.value(it) },
                { activate.value(it) },
            )
        }
        .onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-list-content-key")) { listCredentials }
        .onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-space-directory-content-key")) {
            spaceDirectoryCredentials
        }
        .onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-receipt-content-key")) { receiptCredentials }
        .onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-history-content-key")) { historyCredentials }
        .onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-notification-input-key")) {
            notificationInputCredentials
        }
        .onClose { it?.close() }
}
