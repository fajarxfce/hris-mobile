package dev.fajar.starter.app.work

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingStartup
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.worker.ForegroundSyncWorker
import dev.fajar.starter.worker.ForegroundWorkScheduler
import kotlinx.coroutines.*
import org.koin.core.KoinApplication

/** Called once by a non-Android application host, with its explicitly owned scope. */
fun startForegroundSync(container: KoinApplication, scope: CoroutineScope) =
    scope.launch {
        supervisorScope {
            ForegroundSyncWorker(
                    container.koin.getAll<SyncTask>(),
                    container.koin.get<WorkScheduler>() as ForegroundWorkScheduler,
                )
                .start(this)
            container.koin.get<ApplicationCollaborationIncomingStartup>().start(this)
        }
    }
