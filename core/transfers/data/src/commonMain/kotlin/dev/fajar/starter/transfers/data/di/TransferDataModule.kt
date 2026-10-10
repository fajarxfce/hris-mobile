package dev.fajar.starter.transfers.data.di

import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.transfers.domain.repositories.*
import dev.fajar.starter.transfers.domain.usecases.*
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.transfers.data")
class TransferDataModule {
    @Factory
    fun read(sessions: SessionRepository, queue: TransferQueueRepository) =
        ReadDownloadedFile(sessions, queue)

    @Factory
    fun observe(sessions: SessionRepository, queue: TransferQueueRepository) =
        ObserveTransfers(sessions, queue)

    @Factory
    fun upload(
        sessions: SessionRepository,
        access: AccessRepository,
        inputs: TransferInputRepository,
        queue: TransferQueueRepository,
        scheduler: SyncScheduleRepository,
    ) = EnqueueUpload(sessions, access, inputs, queue, scheduler)

    @Factory
    fun download(
        sessions: SessionRepository,
        access: AccessRepository,
        gateway: TransferGatewayRepository,
        queue: TransferQueueRepository,
        scheduler: SyncScheduleRepository,
    ) = EnqueueDownload(sessions, access, gateway, queue, scheduler)

    @Factory
    fun change(
        sessions: SessionRepository,
        queue: TransferQueueRepository,
        scheduler: SyncScheduleRepository,
    ) = ChangeTransfer(sessions, queue, scheduler)

    @Single(binds = [SyncTask::class, SyncTransfers::class])
    fun sync(
        sessions: SessionRepository,
        access: AccessRepository,
        queue: TransferQueueRepository,
        gateway: TransferGatewayRepository,
    ) = SyncTransfers(sessions, access, queue, gateway)
}
