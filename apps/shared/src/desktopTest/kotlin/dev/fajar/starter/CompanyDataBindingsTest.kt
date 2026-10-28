package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import java.nio.file.Files
import kotlin.test.*
import org.koin.dsl.module

class CompanyDataBindingsTest {
    @Test
    fun eachDatasetOwnsItsObservationAndSharesAcquisitionWithTheRegisteredWorker() {
        val directory = Files.createTempDirectory("company-data-bindings").toFile()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
            )
        try {
            val koin = container.koin
            val observers = koin.getAll<ObserveCompanyData>()
            val acquisitions = koin.getAll<SyncCompanyData>()
            val tasks = koin.getAll<SyncTask>()
            assertEquals(CompanyDataSet.entries.toSet(), observers.map { it.dataSet }.toSet())
            assertEquals(CompanyDataSet.entries.toSet(), acquisitions.map { it.dataSet }.toSet())
            assertEquals(CompanyDataSet.entries.size, observers.size)
            assertEquals(CompanyDataSet.entries.size, acquisitions.size)
            acquisitions.forEach { sync -> assertSame(sync, tasks.single { it.key == sync.key }) }
            val pending = koin.getAll<ObservePendingActions>()
            assertEquals(PendingActionGroup.entries.toSet(), pending.map { it.group }.toSet())
            assertEquals(PendingActionGroup.entries.size, pending.size)
        } finally {
            container.close()
            directory.deleteRecursively()
        }
    }
}
