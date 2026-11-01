package dev.fajar.starter.app.di

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AtomicCredentialStore
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The application host supplies platform resources; feature bindings remain in collaboration. */
internal fun applicationCollaborationModule(
    runtime: (IdentityServer) -> CollaborationRuntimeRepository,
    listCredentials: AtomicCredentialStore,
) = module {
    single<CollaborationRuntimeRepository> { runtime(get()) }
    single { ApplicationCollaborationLifetime(get()) }.onClose { it?.close() }
    single<AtomicCredentialStore>(named("collaboration-list-content-key")) { listCredentials }
        .onClose { it?.close() }
}
