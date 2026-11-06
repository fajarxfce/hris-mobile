package dev.fajar.hris.collaboration.proof

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import kotlinx.coroutines.*

internal data class HrisHistoricalMessage(val eventId: String, val body: String) {
    override fun toString() = "HrisHistoricalMessage([redacted])"
}

internal data class HrisForegroundHistoryResult(
    val pages: Int,
    val references: Long,
    val pendingStateReferences: Int,
)

/** Real app ViewModel and use cases; native Activity rendering is covered separately. */
internal class HrisForegroundHistoryProbe(
    private val factory: () -> ChatThreadViewModel,
    private val snapshot: suspend () -> List<CollaborationHistoryMessage>,
    private val references: suspend () -> CollaborationHistoryReferences,
) {
    suspend fun run(
        originalEvent: String,
        originalBody: String,
        historical: List<HrisHistoricalMessage>,
    ): HrisForegroundHistoryResult =
        withContext(Dispatchers.Main) {
            check(historical.size == 175 && historical.map { it.eventId }.distinct().size == 175)
            val oldest = historical.first()
            val latest = historical.last()
            check(snapshot().single { oldest.eventId in it.eventIds }.text == oldest.body)
            val store = ViewModelStore()
            val provider =
                ViewModelProvider.create(store, viewModelFactory { initializer { factory() } })
            val model = provider[ChatThreadViewModel::class]
            try {
                model.onEvent(ChatThreadEvent.Entered())
                await(HrisForegroundPhase.LATEST, model) {
                    healthy(model.state.value)
                    model.state.value.connection == ChatThreadConnection.CONNECTED &&
                        snapshot().any { latest.eventId in it.eventIds }
                }
                // Incoming recovery has retained every original; Browse still has its own cursor.
                check(snapshot().single { oldest.eventId in it.eventIds }.text == oldest.body)
                var pages = 0
                var localWindows = 0
                while (model.state.value.hasOlder && pages + localWindows < 16) {
                    val before = references().progress
                    val rows = model.state.value.rows.map { it.eventId }
                    model.onEvent(ChatThreadEvent.OlderRequested)
                    await(HrisForegroundPhase.OLDER, model) {
                        healthy(model.state.value)
                        (references().progress != before ||
                            model.state.value.rows.map { it.eventId } != rows) &&
                            !model.state.value.loadingOlder &&
                            !model.state.value.syncing
                    }
                    if (references().progress != before) pages++ else localWindows++
                }
                check(pages in 2..12 && localWindows >= 1 && !model.state.value.hasOlder)
                val saved = snapshot()
                check(saved.single { originalEvent in it.eventIds }.text == originalBody)
                for (event in historical) check(
                    saved.single { event.eventId in it.eventIds }.text == event.body
                )
                val pending = references()
                val progress = requireNotNull(pending.progress)
                check(
                    progress.exhausted && progress.references in 176..224 && pending.after == null
                )
                check(
                    pending.events.none {
                        it.event.type in setOf("m.room.encrypted", "m.room.message", "m.sticker")
                    }
                )
                model.onEvent(ChatThreadEvent.Stopped)
                await(HrisForegroundPhase.OFFLINE, model) {
                    val state = model.state.value
                    healthy(state)
                    state.connection == ChatThreadConnection.OFFLINE &&
                        state.rows.any { it.eventId == oldest.eventId && it.text == oldest.body }
                }
                check(!model.state.value.historyIncomplete)
                HrisForegroundHistoryResult(pages, progress.references, pending.events.size)
            } finally {
                // Application runtime shutdown subsequently verifies child draining, not just this
                // request.
                store.clear()
            }
        }

    private fun healthy(state: ChatThreadState) {
        (state.localFailure ?: state.historyFailure ?: state.failure)?.let {
            throw HrisProofFailure(it)
        }
    }

    private suspend fun await(
        phase: HrisForegroundPhase,
        model: ChatThreadViewModel,
        ready: suspend () -> Boolean,
    ) {
        val completed =
            withTimeoutOrNull(45_000) {
                while (!ready()) delay(50)
                true
            }
        if (completed != true) {
            val state = model.state.value
            throw HrisForegroundTimeout(phase, state.connection, state.rows.size, snapshot().size)
        }
    }
}

internal enum class HrisForegroundPhase {
    LATEST,
    OLDER,
    OFFLINE,
}

/** Only fixture phase, enum state and bounded counters; no message content or identifiers. */
internal class HrisForegroundTimeout(
    phase: HrisForegroundPhase,
    connection: ChatThreadConnection,
    rows: Int,
    saved: Int,
) : IllegalStateException("Owned foreground history timed out") {
    val category =
        "Foreground_${phase.name}_${connection.name}_Rows${rows.coerceIn(0, 999)}_Saved${saved.coerceIn(0, 999)}"
}
