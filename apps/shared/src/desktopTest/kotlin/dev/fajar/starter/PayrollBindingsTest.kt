package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.payroll.domain.usecases.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

class PayrollBindingsTest {
    @Test
    fun isolatedApplicationResolvesPayrollAndRejectsReadsWithoutASession() = runBlocking {
        val directory = Files.createTempDirectory("payroll-bindings").toFile()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
            )
        try {
            val koin = container.koin
            assertSame<Any>(koin.get<PayslipRepository>(), koin.get<PayslipPdfRepository>())
            assertNotNull(koin.get<PayslipCacheRepository>())
            val id = "44444444-4444-4444-4444-444444444444"
            val results =
                listOf(
                    koin.get<RefreshPayslips>()("workspace", PayslipSelection(2026)),
                    koin.get<RefreshPayslip>()("workspace", id),
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
    }
}
