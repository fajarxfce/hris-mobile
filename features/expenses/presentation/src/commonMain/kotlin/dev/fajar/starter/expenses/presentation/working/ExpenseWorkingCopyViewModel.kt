package dev.fajar.starter.expenses.presentation.working

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.expenseFailureRestrictsWorkingCopy
import dev.fajar.starter.expenses.domain.working.usecases.SaveExpenseWorkingCopy
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*

/**
 * The editing ViewModel owns one serial writer. New input replaces only the waiting snapshot; an
 * in-flight mutation is retained until acknowledged, including after an uncertain local commit.
 */
abstract class ExpenseWorkingCopyViewModel<State : ExpenseEditorState, Event : Any, Effect : Any>(
    initial: State,
    private val workspaceId: String,
    private val slot: ExpenseWorkingSlot,
    private val save: SaveExpenseWorkingCopy,
) : MviViewModel<State, Event, Effect>(initial) {
    private var localSave: Job? = null

    protected abstract fun withPersistence(state: State, value: ExpenseEditPersistence?): State

    protected abstract fun onLocalAccessFailure(failure: Failure)

    protected abstract suspend fun onLocalCopySaved(
        copy: ExpenseWorkingCopy,
        mutation: ExpenseWorkingMutation,
    )

    protected fun persistEdits(immediate: Boolean = false) {
        val local = state.value.persistence ?: return
        if (
            state.value.closing ||
                state.value.restriction != null ||
                local.saved ||
                local.failure != null
        )
            return
        if (localSave?.isActive == true) {
            if (!immediate || local.writing) return
            // Cancelling a debounce is safe; an admitted write must finish before another begins.
            localSave?.cancel()
        }
        localSave =
            viewModelScope.launch {
                if (!immediate) delay(250)
                while (isActive) {
                    val current = state.value.persistence ?: return@launch
                    if (
                        current.saved ||
                            current.failure != null ||
                            state.value.restriction != null ||
                            state.value.closing
                    )
                        return@launch
                    val mutation =
                        current.pending ?: ExpenseWorkingMutation(current.stored, current.edited)
                    updateState {
                        withPersistence(
                            it,
                            it.persistence?.copy(pending = mutation, writing = true),
                        )
                    }
                    val result =
                        save(
                            workspaceId,
                            slot,
                            mutation.previous,
                            mutation.updated,
                            mutation.reload,
                        )
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            reportLocalFailure(result.failure)
                            return@launch
                        }
                        is AppResult.Success -> {
                            updateState {
                                withPersistence(it, it.persistence?.acknowledge(result.value))
                            }
                            onLocalCopySaved(result.value, mutation)
                        }
                    }
                }
            }
    }

    protected suspend fun flushLocalEdits(): Boolean {
        persistEdits(immediate = true)
        awaitLocalSave()
        return state.value.persistence?.saved != false && state.value.restriction == null
    }

    protected suspend fun awaitLocalSave() {
        localSave?.join()
        currentCoroutineContext().ensureActive()
    }

    protected fun cancelLocalSave() {
        localSave?.cancel()
    }

    protected fun reportLocalFailure(failure: Failure) {
        updateState {
            withPersistence(it, it.persistence?.copy(failure = failure, writing = false))
        }
        if (expenseFailureRestrictsWorkingCopy(failure)) onLocalAccessFailure(failure)
    }
}
