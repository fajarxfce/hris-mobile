@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.files.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.files.presentation.queue.*
import dev.fajar.starter.security.domain.access.usecases.*
import dev.fajar.starter.transfers.domain.entities.*
import dev.fajar.starter.transfers.domain.repositories.TransferInputRepository
import dev.fajar.starter.transfers.domain.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class FilesViewModelTest {
    @Test
    fun progressIsConflatedAndRouteStopDisposesAllObservers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sessions = TestSessions()
        val queue = TestQueue()
        val access = TestAccess()
        val scheduler = TestScheduler()
        val source =
            object : TransferInputRepository {
                override suspend fun metadata(sourceId: String) = error("unused")

                override fun read(sourceId: String) = error("unused")
            }
        val transfer =
            testTransfer()
                .copy(
                    file = TransferFile("file", "text/plain", 10_000),
                    status = TransferStatus.Running,
                )
        queue.rows.value = listOf(transfer)
        val viewModel =
            FilesViewModel(
                ObserveTransfers(sessions, queue),
                EnqueueUpload(sessions, access, source, queue, scheduler),
                EnqueueDownload(sessions, access, TestGateway(), queue, scheduler),
                ChangeTransfer(sessions, queue, scheduler),
                ObservePermission(sessions, access),
                RefreshAccess(sessions, access),
            )
        val owner = ViewModelStore().apply { put("files", viewModel) }
        try {
            val snapshots = mutableListOf<FilesState>()
            backgroundScope.launch { viewModel.state.collect { snapshots += it } }
            viewModel.onEvent(FilesEvent.Started)
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(0, viewModel.state.value.rows.single().progress)
            assertEquals(1, queue.collectors)
            val before = snapshots.size
            repeat(9) {
                queue.rows.value =
                    listOf(transfer.copy(offset = it + 1L, checkpointVersion = it + 1L))
                runCurrent()
            }
            advanceTimeBy(200)
            runCurrent()
            assertEquals(before, snapshots.size)
            repeat(10) {
                queue.rows.value = listOf(transfer.copy(offset = (it + 1) * 1000L))
                runCurrent()
                advanceTimeBy(5)
            }
            assertEquals(before, snapshots.size)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(before + 1, snapshots.size)
            assertEquals(100, viewModel.state.value.rows.single().progress)
            viewModel.onEvent(FilesEvent.Stopped)
            runCurrent()
            assertEquals(0, queue.collectors)
            assertEquals(0, sessions.collectors)
            viewModel.onEvent(FilesEvent.Started)
            runCurrent()
            assertEquals(1, queue.collectors)
            owner.clear()
            runCurrent()
            assertEquals(0, queue.collectors)
            assertEquals(0, sessions.collectors)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}
