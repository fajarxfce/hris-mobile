package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.securestorage.MemoryCredentialStore
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workforce.domain.attendance.usecases.*
import dev.fajar.starter.workforce.domain.replica.repositories.*
import dev.fajar.starter.workforce.domain.replica.usecases.SyncWorkforce
import dev.fajar.starter.workforce.domain.usecases.ObserveWorkCalendar
import java.nio.file.Files
import kotlin.test.*
import org.koin.dsl.module

class WorkforceBindingsTest {
    @Test
    fun allReadAndCommandConsumersUseTheRegisteredReplicaAndTheWorkerSharesOneSyncOwner() {
        val directory = Files.createTempDirectory("workforce-bindings").toFile()
        val key = ClosingWorkforceCredentialStore()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
                workforceReplicaCredentials = key,
            )
        try {
            val koin = container.koin
            assertNotNull(koin.get<WorkforceReplicaRepository>())
            assertNotNull(koin.get<WorkforceRecordRepository>())
            assertNotNull(koin.get<ObserveWorkCalendar>())
            assertNotNull(koin.get<ObserveAttendance>())
            assertNotNull(koin.get<CaptureAttendance>())
            assertNotNull(koin.get<ReplayAttendance>())
            assertSame(
                koin.get<SyncWorkforce>(),
                koin.getAll<SyncTask>().single { it.key == SyncWorkforce.KEY },
            )
        } finally {
            container.close()
            directory.deleteRecursively()
        }
        assertEquals(1, key.closes)
    }
}

private class ClosingWorkforceCredentialStore : CredentialStore by MemoryCredentialStore() {
    var closes = 0

    override fun close() {
        closes++
    }
}
