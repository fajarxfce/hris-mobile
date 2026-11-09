package dev.fajar.starter.app.di

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.work.CollaborationSpaceRecoveryWorker
import dev.fajar.starter.collaboration.domain.usecases.AdoptCollaborationSpaceRuntime
import dev.fajar.starter.collaboration.domain.usecases.DeliverCollaborationSpaceOriginalMessages
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSpaceRecoveryDevice
import dev.fajar.starter.collaboration.domain.usecases.SynchronizeCollaborationSpaceDevice
import dev.fajar.starter.sync.domain.SyncTask
import org.koin.dsl.bind
import org.koin.dsl.module

/** Application binding for the independent organization recovery task. */
internal fun applicationCollaborationSpaceWorkerModule() = module {
    single {
        val parent = inject<ApplicationCollaborationLifetime>()
        val device = inject<LoadCollaborationSpaceRecoveryDevice>()
        val adopt = inject<AdoptCollaborationSpaceRuntime>()
        val sync = inject<SynchronizeCollaborationSpaceDevice>()
        val deliver = inject<DeliverCollaborationSpaceOriginalMessages>()
        CollaborationSpaceRecoveryWorker(
            states = lazy { parent.value.state },
            ensureWake = get(),
            readWake = get(),
            completeWake = get(),
            resume = inject(),
            device = { device.value(it) },
            adopt = { adopt.value(it.owner, it.registrationId, it.operationId) },
            sync = { sync.value(it) },
            readProgress = get(),
            updateProgress = get(),
            page = get(),
            room = get(),
            finishSweep = get(),
            deliver = { runtime, conversation -> deliver.value(runtime, conversation) },
            build = get(),
        )
    } bind SyncTask::class
}
