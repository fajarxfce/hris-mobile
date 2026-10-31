package dev.fajar.starter.collaboration.data.datasources

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.*

/** Raw classic-sync binding. The SDK persists updates; this listener owns no UI callback. */
internal suspend fun startAndroidCollaborationSync(client: Client): CollaborationSdkSyncConnection {
    val handle =
        client.syncV2(
            SyncSettingsV2(timeoutMs = 10_000uL, fullState = false),
            object : SyncListenerV2 {
                override fun onUpdate(response: SyncResponseV2) = Unit
            },
        )
    return AndroidCollaborationSyncConnection(handle)
}

private class AndroidCollaborationSyncConnection(private var task: TaskHandle?) :
    CollaborationSdkSyncConnection {
    override suspend fun stop() {
        val current = task ?: return
        current.cancel()
        // Keep the handle after a timeout/cancellation. The lease keeps its store lock
        // until a later explicit close observes that the native producer has stopped.
        checkNotNull(
            withTimeoutOrNull(5_000) {
                while (!current.isFinished()) delay(10)
                Unit
            }
        ) {
            "Native synchronization is still stopping."
        }
        try {
            current.close()
        } finally {
            task = null
        }
    }
}
