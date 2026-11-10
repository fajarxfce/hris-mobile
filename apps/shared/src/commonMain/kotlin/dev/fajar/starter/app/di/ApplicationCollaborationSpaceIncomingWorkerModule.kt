package dev.fajar.starter.app.di

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingStartup
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.work.CollaborationSpaceIncomingWorker
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import org.koin.dsl.bind
import org.koin.dsl.module

/** Application task binding; feature policy and repository construction remain in data DI. */
internal fun applicationCollaborationSpaceIncomingWorkerModule() = module {
    single {
        val parent = inject<ApplicationCollaborationLifetime>()
        val observe = inject<ObserveCollaborationSpaceIncomingStartup>()
        val request = inject<RequestCollaborationSpaceIncomingScan>()
        ApplicationCollaborationIncomingStartup(
            states = lazy { parent.value.state },
            observe = { observe.value(it) },
            request = { access, generation, reason -> request.value(access, generation, reason) },
            build = get(),
        )
    }
    single {
        val parent = inject<ApplicationCollaborationLifetime>()
        val ensureWake = inject<EnsureCollaborationSpaceIncomingWake>()
        val readWake = inject<ReadCollaborationSpaceIncomingWake>()
        val requestScan = inject<RequestCollaborationSpaceIncomingScan>()
        val readDemand = inject<ReadCollaborationSpaceIncomingDemand>()
        val discover = inject<DiscoverCollaborationSpaceIncomingRooms>()
        val finish = inject<FinishCollaborationSpaceIncomingWork>()
        val resume = inject<ResumeCollaborationSpaceIncomingRecovery>()
        val device = inject<LoadCollaborationSpaceRecoveryDevice>()
        val adopt = inject<AdoptCollaborationSpaceRuntime>()
        val room = inject<LoadCollaborationSpaceIncomingRoom>()
        val acquirePage = inject<AcquireCollaborationSpaceProviderHistory>()
        val advance = inject<AdvanceCollaborationSpaceIncomingProvider>()
        val readReferences = inject<ReadCollaborationSpaceIncomingReferences>()
        val acquireEvents = inject<AcquireCollaborationSpaceEvents>()
        val completeReferences = inject<CompleteCollaborationSpaceIncomingReferences>()
        val completeRoom = inject<CompleteCollaborationSpaceIncomingRoom>()
        val recoverProvider = inject<RecoverCollaborationSpaceIncomingProvider>()
        val defer = inject<DeferCollaborationSpaceIncomingRoom>()
        CollaborationSpaceIncomingWorker(
            states = lazy { parent.value.state },
            ensureWake = { ensureWake.value() },
            readWake = { readWake.value() },
            requestScan = { a, b -> requestScan.value(a, b) },
            readDemand = { a -> readDemand.value(a) },
            discover = { a -> discover.value(a) },
            finish = { a, b -> finish.value(a, b) },
            resume = { resume.value() },
            device = { a -> device.value(a) },
            adopt = { a -> adopt.value(a.owner, a.registrationId, a.operationId) },
            room = { a, b -> room.value(a, b) },
            acquirePage = { a, b, c -> acquirePage.value(a, b, c) },
            advance = { a, b, c -> advance.value(a, b, c) },
            readReferences = { a, b -> readReferences.value(a, b) },
            acquireEvents = { a, b, c -> acquireEvents.value(a, b, c) },
            completeReferences = { a, b, c, d -> completeReferences.value(a, b, c, d) },
            completeRoom = { a, b, c -> completeRoom.value(a, b, c) },
            recoverProvider = { a, b, c -> recoverProvider.value(a, b, c) },
            defer = { a, b, c -> defer.value(a, b, c) },
            build = get(),
        )
    } bind SyncTask::class
}
