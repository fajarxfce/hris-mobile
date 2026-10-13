package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.leave.domain.usecases.*
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.securestorage.MemoryCredentialStore
import dev.fajar.starter.sync.domain.SyncTask
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

class LeaveBindingsTest {
    @Test
    fun syncSharesOneExecutionOwnerAndBothContentStoresCloseWithTheApplication() = runBlocking {
        val directory = Files.createTempDirectory("leave-bindings").toFile()
        val legacy = ClosingLeaveCredentialStore()
        val replica = ClosingLeaveCredentialStore()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
                leaveCredentials = legacy,
                leaveReplicaCredentials = replica,
            )
        try {
            val koin = container.koin
            assertNotNull(koin.get<LeaveCacheRepository>())
            assertNotNull(koin.get<LeaveReplicaRepository>())
            assertSame(
                koin.get<LeaveReplicaRepository>(),
                koin.get<LeaveRequestReplicaRepository>(),
            )
            assertNotNull(koin.get<LoadLeaveRequest>())
            assertNotNull(koin.get<LeaveRecordRepository>())
            assertSame(
                koin.get<SyncLeave>(),
                koin.getAll<SyncTask>().single { it.key == SyncLeave.KEY },
            )
            val results =
                listOf(
                    koin.get<SyncLeave>()("workspace"),
                    koin.get<ObserveLeave>()("workspace").first(),
                )
            results.forEach {
                assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(it).failure.kind)
            }
        } finally {
            container.close()
            directory.deleteRecursively()
        }
        assertEquals(1, legacy.closes)
        assertEquals(1, replica.closes)
    }
}

private class ClosingLeaveCredentialStore : CredentialStore by MemoryCredentialStore() {
    var closes = 0

    override fun close() {
        closes++
    }
}
