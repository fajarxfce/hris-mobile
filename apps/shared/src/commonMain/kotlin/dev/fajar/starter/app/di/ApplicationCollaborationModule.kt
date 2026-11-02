package dev.fajar.starter.app.di

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AtomicCredentialStore
import io.ktor.client.HttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The application host supplies platform resources; feature bindings remain in collaboration. */
internal fun applicationCollaborationModule(
    runtime: (IdentityServer, HttpClient) -> CollaborationRuntimeRepository,
    listCredentials: AtomicCredentialStore,
    receiptCredentials: AtomicCredentialStore,
    historyCredentials: AtomicCredentialStore,
) = module {
    single<CollaborationRuntimeRepository> {
        runtime(get(), get(named(dev.fajar.starter.network.HttpClients.Public)))
    }
    single { ApplicationCollaborationLifetime(get(), get(), get(), get()) }.onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-list-content-key")) { listCredentials }
        .onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-receipt-content-key")) { receiptCredentials }
        .onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-history-content-key")) { historyCredentials }
        .onClose { it?.close() }
}
