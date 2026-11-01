package dev.fajar.starter.app.di

import dev.fajar.starter.app.work.CollaborationDeliveryWorker
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
    single {
        CollaborationDeliveryWorker(
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
        )
    } bind SyncTask::class
}
