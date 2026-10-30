package dev.fajar.starter.documents.presentation.editor

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.working.usecases.SaveDocumentMetadataWorkingCopy
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlin.time.Clock
import kotlinx.coroutines.*

/** One writer per editor; a lost acknowledgement retains the exact pending mutation. */
abstract class DocumentMetadataWorkingViewModel(
    private val workspaceId: String,
    private val save: SaveDocumentMetadataWorkingCopy,
) :
    MviViewModel<
        DocumentMetadataEditorState,
        DocumentMetadataEditorEvent,
        DocumentMetadataEditorEffect,
    >(DocumentMetadataEditorState()) {
    private var writer: Job? = null
    private var writerVersion = 0

    protected abstract fun report(failure: Failure)

    protected fun persistEdits(immediate: Boolean = false) {
        val local = state.value.persistence ?: return
        if (
            local.saved ||
                local.failure != null ||
                state.value.restriction != null ||
                state.value.closing
        )
            return
        if (writer?.isActive == true) {
            if (!immediate || local.writing) return
            writer?.cancel()
        }
        val owner = ++writerVersion
        writer =
            viewModelScope.launch {
                if (!immediate) delay(250)
                try {
                    while (isActive) {
                        val current = state.value.persistence ?: return@launch
                        if (
                            current.saved ||
                                current.failure != null ||
                                state.value.restriction != null ||
                                state.value.closing
                        )
                            return@launch
                        val mutation = current.mutation(Clock.System.now())
                        updateState {
                            it.copy(
                                persistence =
                                    it.persistence?.copy(pending = mutation, writing = true)
                            )
                        }
                        when (val result = save(workspaceId, mutation.expected, mutation.next)) {
                            is AppResult.Failed -> {
                                ensureActive()
                                updateState {
                                    it.copy(
                                        persistence =
                                            it.persistence?.copy(
                                                failure = result.failure,
                                                writing = false,
                                            )
                                    )
                                }
                                report(result.failure)
                                return@launch
                            }
                            is AppResult.Success -> {
                                ensureActive()
                                updateState {
                                    it.copy(
                                        persistence =
                                            it.persistence?.acknowledge(result.value, mutation)
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    if (owner == writerVersion)
                        updateState { it.copy(persistence = it.persistence?.copy(writing = false)) }
                }
            }
    }

    protected suspend fun flushEdits(): Boolean {
        persistEdits(immediate = true)
        writer?.join()
        currentCoroutineContext().ensureActive()
        return state.value.persistence?.saved != false && state.value.restriction == null
    }

    protected fun cancelWriter() {
        writerVersion++
        writer?.cancel()
        writer = null
    }
}
