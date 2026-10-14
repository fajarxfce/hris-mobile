package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.payroll.domain.replica.usecases.SyncPayroll
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.payroll.domain.usecases.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.SyncTask
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

class PayrollBindingsTest {
    @Test
    fun isolatedApplicationResolvesPayrollAndRejectsReadsWithoutASession() = runBlocking {
        val directory = Files.createTempDirectory("payroll-bindings").toFile()
        val key = ClosingPayrollCredentialStore()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
                payrollReplicaCredentials = key,
            )
        try {
            val koin = container.koin
            assertSame<Any>(koin.get<PayslipRepository>(), koin.get<PayslipPdfRepository>())
            assertNotNull(koin.get<PayslipCacheRepository>())
            assertNotNull(koin.get<PayslipReplicaRepository>())
            assertSame(
                koin.get<SyncPayroll>(),
                koin.getAll<SyncTask>().single { it.key == SyncPayroll.KEY },
            )
            val id = "44444444-4444-4444-4444-444444444444"
            val results =
                listOf(
                    koin.get<SyncPayroll>()("workspace"),
                    koin.get<ObservePayslips>()("workspace").first(),
                    koin.get<ObservePayslip>()("workspace", id).first(),
                    koin.get<ExportPayslip>()("workspace", id, PayslipDocumentLanguage.ID),
                )
            results.forEach {
                assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(it).failure.kind)
            }
        } finally {
            container.close()
            directory.deleteRecursively()
        }
        assertEquals(1, key.closes)
    }
}

private class ClosingPayrollCredentialStore(
    private val delegate: CredentialStore = MemoryCredentialStore()
) : CredentialStore by delegate {
    var closes = 0

    override fun close() {
        closes++
        delegate.close()
    }
}
