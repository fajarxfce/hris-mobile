package dev.fajar.starter.sync.data

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.data.di.SyncDataModule
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.ksp.generated.module

class SyncScheduleRepositoryTest {
    @Test
    fun taskRegistrationCanInspectTasksWhichScheduleSubsequentWork() = runTest {
        for (resolveTaskFirst in listOf(true, false)) {
            var registrations = 0
            val requested = mutableListOf<String>()
            val container = koinApplication {
                modules(
                    SyncDataModule().module,
                    module {
                        single<SyncTask> {
                            val schedule = get<SyncScheduleRepository>()
                            object : SyncTask {
                                override val key = "dependent-task"

                                override suspend fun invoke(): SyncResult {
                                    assertIs<AppResult.Success<Unit>>(schedule.request(key))
                                    return SyncResult.Complete
                                }
                            }
                        }
                        single<WorkScheduler> {
                            registrations++
                            val registered = getAll<SyncTask>().map { it.key }.toSet()
                            object : WorkScheduler {
                                override suspend fun enqueue(key: String) {
                                    check(key in registered)
                                    requested += key
                                }
                            }
                        }
                    },
                )
            }
            try {
                if (resolveTaskFirst) container.koin.getAll<SyncTask>()
                else container.koin.get<SyncScheduleRepository>()
                assertEquals(0, registrations)
                val task = container.koin.getAll<SyncTask>().single()
                assertEquals(SyncResult.Complete, task())
                assertEquals(SyncResult.Complete, task())
                assertEquals(listOf(task.key, task.key), requested)
                assertEquals(1, registrations)
            } finally {
                container.close()
            }
        }
    }
}
