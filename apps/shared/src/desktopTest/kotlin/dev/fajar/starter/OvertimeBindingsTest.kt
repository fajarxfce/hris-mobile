package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.MemoryCredentialStore
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.usecases.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

class OvertimeBindingsTest {
    @Test
    fun syncSharesOneExecutionOwnerAndBothContentStoresCloseWithTheApplication() = runBlocking {
        val directory = Files.createTempDirectory("overtime-bindings").toFile()
        val legacy = ClosingOvertimeCredentialStore()
        val replica = ClosingOvertimeCredentialStore()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
                workspaceCredentials = legacy,
                overtimeReplicaCredentials = replica,
            )
        try {
            val koin = container.koin
            assertNotNull(koin.get<OvertimeCacheRepository>())
            assertNotNull(koin.get<OvertimeReplicaRepository>())
            assertSame(
                koin.get<OvertimeReplicaRepository>(),
                koin.get<OvertimeRequestReplicaRepository>(),
            )
            assertNotNull(koin.get<LoadOvertimeRequest>())
            assertNotNull(koin.get<OvertimeRecordRepository>())
            assertSame(
                koin.get<SyncOvertime>(),
                koin.getAll<SyncTask>().single { it.key == SyncOvertime.KEY },
            )
            val results =
                listOf(
                    koin.get<SyncOvertime>()("workspace"),
                    koin.get<ObserveOvertime>()("workspace").first(),
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

private class ClosingOvertimeCredentialStore : AtomicCredentialStore by MemoryCredentialStore() {
    var closes = 0

    override fun close() {
        closes++
    }
}
