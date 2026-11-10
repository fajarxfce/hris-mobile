@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingStartup
import dev.fajar.starter.app.work.startForegroundSync
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.fixtures.IncomingStartupFixture
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.worker.ForegroundWorkScheduler
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class StartForegroundSyncTest {
    @Test
    fun oneHostJobOwnsStartupAndCancelsItsWorkerRequests() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        val scheduler = ForegroundWorkScheduler(setOf("fixture"))
        var turns = 0
        val container = koinApplication {
            modules(
                module {
                    single<ApplicationCollaborationIncomingStartup> { f.startup }
                    single<WorkScheduler> { scheduler }
                    single<SyncTask> {
                        object : SyncTask {
                            override val key = "fixture"

                            override suspend fun invoke(): SyncResult {
                                turns++
                                return SyncResult.Complete
                            }
                        }
                    }
                }
            )
        }
        f.request = {
            scheduler.enqueue("fixture")
            AppResult.Success(true)
        }
        try {
            assertEquals(0, turns)
            val work = startForegroundSync(container, backgroundScope)
            runCurrent()
            assertTrue(turns > 0)
            assertEquals(1, f.requests.size)
            assertEquals(1, f.subscriptions)
            work.cancelAndJoin()
            val stoppedAt = turns
            scheduler.enqueue("fixture")
            runCurrent()
            assertEquals(stoppedAt, turns)
            assertEquals(0, f.subscriptions)
        } finally {
            scheduler.close()
            container.close()
        }
    }

    @Test
    fun existingPeriodicWorkerDoesNotRestartTheStartupCollector() = runTest {
        val f = IncomingStartupFixture(testScheduler)
        val scheduler = ForegroundWorkScheduler(setOf("fixture"))
        var turns = 0
        val container = koinApplication {
            modules(
                module {
                    single<ApplicationCollaborationIncomingStartup> { f.startup }
                    single<WorkScheduler> { scheduler }
                    single<SyncTask> {
                        object : SyncTask {
                            override val key = "fixture"

                            override suspend fun invoke(): SyncResult {
                                turns++
                                return SyncResult.Complete
                            }
                        }
                    }
                }
            )
        }
        try {
            val work = startForegroundSync(container, backgroundScope)
            runCurrent()
            advanceTimeBy(15 * 60_000L)
            runCurrent()
            assertEquals(2, turns)
            assertEquals(1, f.requests.size)
            assertEquals(1, f.subscriptions)
            work.cancelAndJoin()
        } finally {
            scheduler.close()
            container.close()
        }
    }
}
