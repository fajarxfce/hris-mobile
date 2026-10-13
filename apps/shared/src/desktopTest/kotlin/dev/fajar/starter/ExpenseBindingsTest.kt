package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.koin.dsl.module

class ExpenseBindingsTest {
    @Test
    fun isolatedApplicationResolvesExpenseAndRejectsReadsWithoutASession() = runBlocking {
        val directory = Files.createTempDirectory("expenses-bindings").toFile()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
            )
        try {
            val koin = container.koin
            assertNotNull(koin.get<ExpenseRepository>())
            assertNotNull(koin.get<ExpenseCacheRepository>())
            assertNotNull(koin.get<ExpensePreparationRepository>())
            assertNotNull(koin.get<ExpenseCommandRepository>())
            assertNotNull(koin.get<ExpenseSubmissionRepository>())
            assertNotNull(koin.get<ExpenseReceiptRepository>())
            assertNotNull(koin.get<ExportExpenseReceipt>())
            assertNotNull(koin.get<PrepareExpenseLine>())
            assertNotNull(koin.get<PrepareExpenseDraft>())
            assertNotNull(koin.get<PrepareExpenseAction>())
            assertSame(
                koin.get<ReplayExpenseCommands>(),
                koin.getAll<SyncTask>().single { it.key == ReplayExpenseCommands.KEY },
            )
            val id = "44444444-4444-4444-4444-444444444444"
            val results =
                listOf(
                    koin.get<RefreshExpenses>()("workspace", ExpenseSelection()),
                    koin.get<RefreshExpenseClaim>()("workspace", id),
                    koin.get<ObserveExpenses>()("workspace").first(),
                    koin.get<ObserveExpenseCommand>()("workspace", ExpenseCommandSlot.Creation(id))
                        .first(),
                    koin.get<ReplayExpenseCommands>()("workspace", id),
                    koin.get<ReviewExpenseCommand>()("workspace", id),
                    koin.get<LoadExpenseDraftPreparation>()("workspace", id),
                    koin.get<LoadExpenseCategories>()("workspace", LocalDate(2026, 10, 13)),
                    koin.get<LoadExpenseCostCenters>()("workspace", ""),
                    koin.get<LoadExpenseDraftReferences>()("workspace", id, emptyList()),
                    koin.get<ObserveExpenseClaim>()("workspace", id).first(),
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
