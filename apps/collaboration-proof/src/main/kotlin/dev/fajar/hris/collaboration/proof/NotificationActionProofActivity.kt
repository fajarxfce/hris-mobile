package dev.fajar.hris.collaboration.proof

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import dev.fajar.starter.notifications.data.datasources.AndroidActionNotificationIntents
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/** Emulator-only entry. The application, not this Activity, owns the action callbacks. */
class NotificationActionProofActivity : Activity() {
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifetime.launch {
            try {
                withTimeout(15_000) {
                    val proof = (application as HrisScheduledProofApplication).actionProof
                    val mode = intent.getStringExtra("mode")
                    when {
                        intent.data != null -> proof.open(intent)
                        intent.getBooleanExtra("verify", false) ->
                            proof.verify(requireNotNull(mode))
                        else -> proof.stage(requireNotNull(mode))
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                File(filesDir, "protocol-proof/action-failure.json")
                    .writeText(
                        JSONObject()
                            .put(
                                "category",
                                (failure as? HrisProofFailure)?.category
                                    ?: failure.javaClass.simpleName,
                            )
                            .put(
                                "frames",
                                failure.stackTrace
                                    .filter { it.className.startsWith("dev.fajar.") }
                                    .take(5)
                                    .joinToString {
                                        "${it.className}.${it.methodName}:${it.lineNumber}"
                                    },
                            )
                            .toString()
                    )
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        lifetime.cancel()
        super.onDestroy()
    }
}

/** Another process invokes the OS-owned PendingIntent; it cannot call the application graph. */
class NotificationActionSenderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = requireNotNull(intent.getStringExtra("mode"))
        check(mode in NotificationActionProof.MODES)
        val notification =
            getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single {
                    it.tag?.startsWith("owned:${NotificationActionProof.NAMESPACE}:") == true
                }
                .notification
        val pending =
            when (mode) {
                "Open" -> notification.contentIntent
                "Dismiss" -> notification.deleteIntent
                else ->
                    notification.actions[
                            when (mode) {
                                "Reply" -> 0
                                "MarkRead" -> 1
                                else -> 2
                            }]
                        .actionIntent
            }
        val fill = Intent()
        if (mode == "Reply")
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(AndroidActionNotificationIntents.REPLY_TEXT).build()),
                fill,
                Bundle().apply {
                    putCharSequence(
                        AndroidActionNotificationIntents.REPLY_TEXT,
                        NotificationActionProof.TEXT,
                    )
                },
            )
        pending.send(this, 0, fill)
        finish()
    }
}
