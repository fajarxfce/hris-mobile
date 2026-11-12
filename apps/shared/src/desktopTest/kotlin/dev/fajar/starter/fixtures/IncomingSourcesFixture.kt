package dev.fajar.starter.fixtures

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingSources
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestCoroutineScheduler

internal class IncomingSourcesFixture(
    scheduler: TestCoroutineScheduler,
    platform: AppPlatform = AppPlatform.Android,
) {
    val worker = RecoveryWorkerFixture(scheduler, count = 0)
    val startup =
        CollaborationSpaceIncomingStartup(
            CollaborationSpaceOwner(worker.parent, recoveryId(800)),
            recoveryId(801),
            recoveryId(802),
            recoveryId(803),
        )
    val runtime =
        CollaborationSpaceRuntimeReady(startup.owner, startup.deviceId, startup.operationId)
    val publication = CollaborationSpaceRuntimePublication(runtime, recoveryId(804))
    val startups =
        MutableStateFlow<AppResult<CollaborationSpaceIncomingStartup?>>(AppResult.Success(startup))
    val publications =
        MutableStateFlow<AppResult<CollaborationSpaceRuntimePublication?>>(AppResult.Success(null))
    var startupBody: () -> Flow<AppResult<CollaborationSpaceIncomingStartup?>> = { startups }
    var publicationBody: () -> Flow<AppResult<CollaborationSpaceRuntimePublication?>> = {
        publications
    }
    var keyBody: (CollaborationSpaceRuntimeReady) -> Flow<AppResult<Boolean>> = {
        flow { awaitCancellation() }
    }
    var syncBody: (CollaborationSpaceRuntimeReady) -> Flow<AppResult<Boolean>> = {
        flow { awaitCancellation() }
    }
    var afterKeys: suspend () -> Unit = {}
    val parents = mutableListOf<CollaborationNativeParent>()
    val runtimeRequests = mutableListOf<CollaborationSpaceRuntimeReady>()
    val keys = mutableListOf<CollaborationSpaceRuntimeReady>()
    val sync = mutableListOf<CollaborationSpaceRuntimeReady>()
    var startupStops = 0
    var publicationStops = 0
    var keyStops = 0
    var syncStops = 0
    val app =
        ApplicationCollaborationIncomingSources(
            observeStartup = { parent ->
                flow {
                    parents += parent
                    try {
                        emitAll(startupBody())
                    } finally {
                        startupStops++
                    }
                }
            },
            observePublication = { runtime ->
                flow {
                    runtimeRequests += runtime
                    try {
                        emitAll(publicationBody())
                    } finally {
                        publicationStops++
                    }
                }
            },
            retainKeys = { runtime ->
                flow {
                    keys += runtime
                    try {
                        emitAll(keyBody(runtime))
                    } finally {
                        withContext(NonCancellable) { afterKeys() }
                        keyStops++
                    }
                }
            },
            retainSync = { runtime ->
                flow {
                    sync += runtime
                    try {
                        emitAll(syncBody(runtime))
                    } finally {
                        syncStops++
                    }
                }
            },
            build = worker.build.copy(platform = platform),
        )

    fun start(scope: CoroutineScope) = scope.launch { app.collect(worker.states) }

    fun publish() {
        publications.value = AppResult.Success(publication)
    }
}
