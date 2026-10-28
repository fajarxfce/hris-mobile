package dev.fajar.starter.notifications.data.datasources

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** SDK callbacks invalidate the current value; they never replay a retired token. */
internal class PushTokenChanges {
    private val changes = MutableStateFlow(Any())

    fun received() {
        changes.value = Any()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(acquire: suspend () -> String): Flow<String> =
        changes.mapLatest { acquire() }.distinctUntilChanged()
}
