package dev.fajar.hris.collaboration.proof

import kotlinx.coroutines.delay
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SyncListenerV2
import org.matrix.rustcomponents.sdk.SyncResponseV2
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TaskHandle

internal enum class ProofSyncMode(
    val wireName: String,
    val sessionVersion: SlidingSyncVersion,
    val builderVersion: SlidingSyncVersionBuilder,
) {
    NATIVE("native", SlidingSyncVersion.NATIVE, SlidingSyncVersionBuilder.NATIVE),
    CLASSIC("classic", SlidingSyncVersion.NONE, SlidingSyncVersionBuilder.NONE);

    companion object {
        fun parse(value: String): ProofSyncMode = entries.single { it.wireName == value }
    }
}

/** Each experiment owns exactly one sync implementation, including its cancellation. */
internal class ProofSyncOwner(private val mode: ProofSyncMode) : AutoCloseable {
    private var service: SyncService? = null
    private var classic: TaskHandle? = null

    suspend fun start(client: Client) {
        check(service == null && classic == null)
        when (mode) {
            ProofSyncMode.NATIVE -> {
                client.syncService().use { builder -> service = builder.finish() }
                checkNotNull(service).start()
            }
            ProofSyncMode.CLASSIC -> {
                classic =
                    client.syncV2(
                        SyncSettingsV2(timeoutMs = 1_000uL, fullState = false),
                        object : SyncListenerV2 {
                            override fun onUpdate(response: SyncResponseV2) = Unit
                        },
                    )
            }
        }
    }

    // The caller supplies a five-second non-cancellable cleanup budget.
    suspend fun stop() {
        classic?.let { task ->
            task.cancel()
            while (!task.isFinished()) delay(10)
        }
        service?.stop()
    }

    override fun close() {
        classic?.cancel()
        classic?.close()
        service?.close()
    }
}
