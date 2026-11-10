package dev.fajar.starter.fixtures

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingStartup
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestCoroutineScheduler

internal class IncomingStartupFixture(
    scheduler: TestCoroutineScheduler,
    platform: AppPlatform = AppPlatform.Android,
) {
    val base = RecoveryWorkerFixture(scheduler, count = 0)
    val states = lazy { base.states }
    val ready =
        CollaborationSpaceIncomingStartup(
            CollaborationSpaceOwner(base.parent, recoveryId(800)),
            recoveryId(801),
            recoveryId(802),
            recoveryId(803),
        )
    val changes =
        MutableSharedFlow<AppResult<CollaborationSpaceIncomingStartup?>>(replay = 1).apply {
            tryEmit(AppResult.Success(ready))
        }
    val failure = AppResult.Failed(Failure(FailureKind.Storage, "Fixture read unavailable"))
    val requests = mutableListOf<Pair<CollaborationSpaceLocalAccess, String>>()
    val reasons = mutableListOf<CollaborationSpaceIncomingScanReason>()
    val parents = mutableListOf<CollaborationNativeParent>()
    var subscriptions = 0
    var ids = 0
    var beforeObserve: () -> Unit = {}
    var request: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    val startup =
        ApplicationCollaborationIncomingStartup(
            states = states,
            observe = { parent ->
                flow {
                    beforeObserve()
                    parents += parent
                    subscriptions++
                    try {
                        emitAll(changes)
                    } finally {
                        subscriptions--
                    }
                }
            },
            request = { access, id, reason ->
                requests += access to id
                reasons += reason
                request()
            },
            build = base.build.copy(platform = platform),
            generation = { recoveryId(900 + ++ids) },
        )
}
