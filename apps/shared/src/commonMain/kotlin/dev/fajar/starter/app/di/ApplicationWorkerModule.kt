package dev.fajar.starter.app.di

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.work.CollaborationDeliveryWorker
import dev.fajar.starter.app.work.CollaborationDraftRecovery
import dev.fajar.starter.app.work.CollaborationRecoveryWorker
import dev.fajar.starter.app.work.InboxPushWorker
import dev.fajar.starter.app.work.NativePushWorker
import dev.fajar.starter.sync.domain.SyncTask
import org.koin.dsl.bind
import org.koin.dsl.module

/** App entry points share one instance with the scheduler; feature use cases keep their own DI. */
fun applicationWorkerModule() = module {
    // Koin annotations 2.3.1 metadata for an app-level SyncTask bind collides with dependency
    // metadata
    // when Kotlin/Native links the framework. Direct app composition emits no such marker.
    single { InboxPushWorker(get(), get(), inject(), get()) } bind SyncTask::class
    single { NativePushWorker(get(), inject()) } bind SyncTask::class
    single { CollaborationDraftRecovery(get(), get(), get(), get(), get()) }
    single {
        val parent = inject<ApplicationCollaborationLifetime>()
        val delivery = inject<CollaborationDeliveryWorker>()
        CollaborationRecoveryWorker(
            states = lazy { parent.value.state },
            ensureWake = get(),
            readWake = get(),
            completeWake = get(),
            resume = inject(),
            readProgress = get(),
            updateProgress = get(),
            devices = get(),
            admit = get(),
            build = get(),
            recoverCompany = { delivery.value(it) },
        )
    } bind SyncTask::class
    single {
        CollaborationDeliveryWorker(
            recoverDrafts = get(),
            discover = get(),
            advance = get(),
            observeAccess = get(),
            loadSetup = get(),
            ensureRuntime = get(),
            control = get(),
            read = get(),
            prepare = get(),
            replay = get(),
            reconcileRoom = get(),
            enter = get(),
            messages = get(),
            reconcile = get(),
            stage = get(),
            deliver = get(),
            acquire = get(),
            reopenHistory = get(),
            recover = get(),
            retire = get(),
            acquireBackground = get(),
        )
    } bind SyncTask::class
}
