package dev.fajar.starter.home.presentation.datasync

sealed interface DataSyncEffect {
    data object Back : DataSyncEffect

    data object VerifyAccount : DataSyncEffect
}
