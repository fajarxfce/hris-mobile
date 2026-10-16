package dev.fajar.starter.home.presentation.datasync

import dev.fajar.starter.workspace.domain.entities.CompanyDataSet

sealed interface DataSyncEvent {
    data object Entered : DataSyncEvent

    data object Stopped : DataSyncEvent

    data object RefreshRequested : DataSyncEvent

    data class DatasetRefreshRequested(val dataSet: CompanyDataSet) : DataSyncEvent

    data object CancelRequested : DataSyncEvent

    data object VerificationRequested : DataSyncEvent

    data object BackRequested : DataSyncEvent
}
