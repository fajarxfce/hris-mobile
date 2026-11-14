package dev.fajar.starter.collaboration.presentation.spacecontacts

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceDirectoryResponseClosesRead
import dev.fajar.starter.collaboration.domain.policies.selectCollaborationSpaceDirectory
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceDirectory
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

/** One admitted Group store retains the local directory across hub/search entry and tab changes. */
@KoinViewModel
class SpaceContactsViewModel(
    @InjectedParam access: CollaborationSpaceLocalAccess,
    observe: ObserveCollaborationSpaceDirectory,
) :
    MviViewModel<SpaceContactsState, SpaceContactsEvent, SpaceContactsEffect>(
        SpaceContactsState()
    ) {
    private val snapshots = MutableStateFlow<CollaborationSpaceDirectorySnapshot?>(null)
    private val queries = MutableStateFlow("")

    init {
        on<SpaceContactsEvent.Entered>(::onEntered)
        on<SpaceContactsEvent.Stopped>(::onStopped)
        on<SpaceContactsEvent.QueryChanged>(::onQueryChanged)
        on<SpaceContactsEvent.ContactSelected>(::onContactSelected)
        viewModelScope.launch {
            observe(access).collect { result ->
                ensureActive()
                when (result) {
                    is AppResult.Success -> {
                        // Publish immutable lists before their off-main selection begins.
                        snapshots.value =
                            result.value.copy(
                                contacts =
                                    result.value.contacts.map {
                                        it.copy(companies = it.companies.toList())
                                    }
                            )
                        updateState { it.copy(failure = null, restriction = null) }
                    }
                    is AppResult.Failed -> {
                        if (collaborationSpaceDirectoryResponseClosesRead(result.failure)) {
                            snapshots.value = null
                            updateState {
                                it.copy(
                                    contacts = emptyList(),
                                    loading = false,
                                    failure = result.failure,
                                    restriction = result.failure,
                                )
                            }
                        } else
                            updateState {
                                it.copy(loading = false, failure = it.restriction ?: result.failure)
                            }
                    }
                }
            }
        }
        viewModelScope.launch { observeSelection() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeSelection() {
        combine(snapshots, queries) { snapshot, query -> snapshot to query }
            .collectLatest { (snapshot, query) ->
                if (snapshot == null) return@collectLatest
                val selected =
                    withContext(Dispatchers.Default) {
                        selectCollaborationSpaceDirectory(snapshot, query)
                    }
                currentCoroutineContext().ensureActive()
                if (
                    snapshots.value === snapshot &&
                        queries.value == query &&
                        state.value.restriction == null
                ) {
                    updateState {
                        it.copy(contacts = selected, appliedQuery = query, loading = false)
                    }
                }
            }
    }

    private fun onEntered(event: SpaceContactsEvent.Entered) {
        updateState { it.copy(visible = true) }
    }

    private fun onStopped(event: SpaceContactsEvent.Stopped) {
        updateState { it.copy(visible = false) }
    }

    private fun onQueryChanged(event: SpaceContactsEvent.QueryChanged) {
        if (event.query.length > 80 || event.query.any { it.code in 0..31 || it.code in 127..159 })
            return
        queries.value = event.query
        updateState { it.copy(query = event.query) }
    }

    private fun onContactSelected(event: SpaceContactsEvent.ContactSelected) {
        val current = state.value
        if (
            !current.visible ||
                current.loading ||
                current.restriction != null ||
                current.query != current.appliedQuery ||
                current.contacts.none { it.accountId == event.accountId }
        )
            return
        updateState { it.copy(visible = false) }
        viewModelScope.launch { emitEffect(SpaceContactsEffect.OpenContact(event.accountId)) }
    }
}
