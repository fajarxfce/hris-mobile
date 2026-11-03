package dev.fajar.hris.collaboration.proof

import android.app.Application
import androidx.work.Configuration
import dev.fajar.starter.app.notifications.AndroidCollaborationNotificationHost
import dev.fajar.starter.worker.SyncWorkerFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Only the dedicated, separately installed debug proof application uses seeded login inputs. */
class HrisScheduledProofApplication :
    Application(), Configuration.Provider, AndroidCollaborationNotificationHost {
    internal val inputProof by lazy { NotificationInputProof(this) }
    override val notificationReplies
        get() = inputProof.native

    internal var proof: HrisScheduledWorkerProbe? = null
        private set

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(SyncWorkerFactory { requireNotNull(proof).tasks() })
                .build()

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName() != packageName) return
        val directory = File(filesDir, "protocol-proof")
        if (!File(directory, "workmanager-enabled.json").isFile) return
        check(
            android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("sdk")
        )
        val owner = HrisScheduledWorkerProbe(this, directory).also { proof = it }
        // Fixture-only synchronous local ownership initialization; this performs no network I/O.
        runBlocking(Dispatchers.IO) { withTimeout(10_000) { owner.initialize() } }
        owner.observeBackgroundWork()
    }
}
