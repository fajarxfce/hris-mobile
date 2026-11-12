package dev.fajar.starter.app.di

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingSources
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingStartup
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.work.CollaborationSpaceHistoryRecoveryService
import dev.fajar.starter.app.work.CollaborationSpaceIncomingWorker
import dev.fajar.starter.app.work.CollaborationSpaceOriginalIncomingTurn
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import org.koin.dsl.bind
import org.koin.dsl.module

/** Application task binding; feature policy and repository construction remain in data DI. */
internal fun applicationCollaborationSpaceIncomingWorkerModule() = module {
    includes(
        applicationCollaborationSpaceOriginalIncomingTurnModule(),
        applicationCollaborationSpaceHistoryRecoveryServiceModule(),
    )
    single {
        val startup = inject<ObserveCollaborationSpaceIncomingStartup>()
        val publication = inject<ObserveCollaborationSpaceRuntimePublication>()
        val keys = inject<RetainCollaborationSpaceRoomKeys>()
        val sync = inject<RetainCollaborationSpaceSyncBatches>()
        ApplicationCollaborationIncomingSources(
            observeStartup = { startup.value(it) },
            observePublication = { publication.value(it) },
            retainKeys = { keys.value.observe(it) },
            retainSync = { sync.value.observe(it) },
            build = get(),
        )
    }
    single {
        val parent = inject<ApplicationCollaborationLifetime>()
        val incoming = inject<ApplicationCollaborationIncomingSources>()
        val observe = inject<ObserveCollaborationSpaceIncomingStartup>()
        val request = inject<RequestCollaborationSpaceIncomingScan>()
        ApplicationCollaborationIncomingStartup(
            states = lazy { parent.value.state },
            observe = { observe.value(it) },
            request = { access, generation, reason -> request.value(access, generation, reason) },
            onForegroundEntry = { incoming.value.foregroundEntered() },
            build = get(),
        )
    }
    single {
        val parent = inject<ApplicationCollaborationLifetime>()
        val ensureWake = inject<EnsureCollaborationSpaceIncomingWake>()
        val readWake = inject<ReadCollaborationSpaceIncomingWake>()
        val requestScan = inject<RequestCollaborationSpaceIncomingScan>()
        val readDemand = inject<ReadCollaborationSpaceIncomingDemand>()
        val resolveBinding = inject<ResolveCollaborationSpaceIncomingBinding>()
        val deferPhysical = inject<DeferCollaborationSpaceIncomingPhysicalRoom>()
        val inventory = inject<RetainCollaborationSpaceRoomInventory>()
        val deferInventory = inject<DeferCollaborationSpaceInventory>()
        val history = inject<CollaborationSpaceHistoryRecoveryService>()
        val discover = inject<DiscoverCollaborationSpaceIncomingRooms>()
        val finish = inject<FinishCollaborationSpaceIncomingWork>()
        val resume = inject<ResumeCollaborationSpaceIncomingRecovery>()
        val device = inject<LoadCollaborationSpaceRecoveryDevice>()
        val adopt = inject<AdoptCollaborationSpaceRuntime>()
        val room = inject<LoadCollaborationSpaceIncomingRoom>()
        val originalRoom = inject<CollaborationSpaceOriginalIncomingTurn>()
        val acquirePage = inject<AcquireCollaborationSpaceProviderHistory>()
        val advance = inject<AdvanceCollaborationSpaceIncomingProvider>()
        val readReferences = inject<ReadCollaborationSpaceIncomingReferences>()
        val acquireEvents = inject<AcquireCollaborationSpaceEvents>()
        val completeReferences = inject<CompleteCollaborationSpaceIncomingReferences>()
        val completeRoom = inject<CompleteCollaborationSpaceIncomingRoom>()
        val demoteRoom = inject<DemoteCollaborationSpaceIncomingRoom>()
        val recoverProvider = inject<RecoverCollaborationSpaceIncomingProvider>()
        val defer = inject<DeferCollaborationSpaceIncomingRoom>()
        CollaborationSpaceIncomingWorker(
            states = lazy { parent.value.state },
            ensureWake = { ensureWake.value() },
            readWake = { readWake.value() },
            requestScan = { a, b -> requestScan.value(a, b) },
            readDemand = { a -> readDemand.value(a) },
            readRuntimeDemand = { a -> readDemand.value(a) },
            resolveBinding = { a, b -> resolveBinding.value(a, b) },
            deferPhysical = { a, b, c -> deferPhysical.value(a, b, c) },
            inventory = { a, b -> inventory.value(a, b) },
            deferInventory = { a, b, c -> deferInventory.value(a, b, c) },
            recoverHistory = { runtime -> history.value(runtime) },
            discover = { a -> discover.value(a) },
            finish = { a, b -> finish.value(a, b) },
            resume = { resume.value() },
            device = { a -> device.value(a) },
            adopt = { a -> adopt.value(a.owner, a.registrationId, a.operationId) },
            room = { a, b -> room.value(a, b) },
            resolveLogicalBinding = { a, b -> resolveBinding.value(a, b) },
            originalRoom = { a, b -> originalRoom.value(a, b) },
            acquirePage = { a, b, c -> acquirePage.value(a, b, c) },
            advance = { a, b, c -> advance.value(a, b, c) },
            readReferences = { a, b -> readReferences.value(a, b) },
            acquireEvents = { a, b, c -> acquireEvents.value(a, b, c) },
            completeReferences = { a, b, c, d -> completeReferences.value(a, b, c, d) },
            completeRoom = { a, b, c -> completeRoom.value(a, b, c) },
            demoteRoom = { runtime, room -> demoteRoom.value(runtime, room) },
            recoverProvider = { a, b, c -> recoverProvider.value(a, b, c) },
            defer = { a, b, c -> defer.value(a, b, c) },
            build = get(),
        )
    } bind SyncTask::class
}
