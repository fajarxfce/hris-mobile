package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.repositories.*
import dev.fajar.starter.expenses.domain.usecases.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            val id = "44444444-4444-4444-4444-444444444444"
            val results =
                listOf(
                    koin.get<RefreshExpenses>()("workspace", ExpenseSelection()),
                    koin.get<RefreshExpenseClaim>()("workspace", id),
                    koin.get<ObserveExpenses>()("workspace").first(),
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
