package dev.fajar.starter.files.presentation.queue

import dev.fajar.starter.common.result.Failure

data class FilesState(
    val rows: List<TransferRow> = emptyList(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    val downloading: Boolean = false,
    val changingId: String? = null,
    val canUpload: Boolean = false,
    val canDownload: Boolean = false,
    val error: Failure? = null,
)
