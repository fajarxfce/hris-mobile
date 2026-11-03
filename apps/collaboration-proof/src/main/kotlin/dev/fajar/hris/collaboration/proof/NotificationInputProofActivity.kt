package dev.fajar.hris.collaboration.proof

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.core.app.RemoteInput
import dev.fajar.starter.notifications.data.datasources.AndroidActionNotificationIntents
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

/** Normal application process, independent of the instrumentation process lifecycle. */
class NotificationInputProofActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            try {
                withTimeout(15_000) {
                    (application as HrisScheduledProofApplication).inputProof.stage()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                File(filesDir, "protocol-proof/reply-failure.json")
                    .writeText(
                        JSONObject()
                            .put("category", failure.javaClass.simpleName)
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
        scope.cancel()
        super.onDestroy()
    }
}

/** Separate process reads the OS-owned PendingIntent after the receiver's process was killed. */
class NotificationInputSenderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "reply"
        require(mode in setOf("reply", "missing", "nonreply"))
        val notification =
            getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single { it.tag?.startsWith("owned:${NotificationInputProof.NAMESPACE}:") == true }
                .notification
        val fill = Intent()
        if (mode != "missing")
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(AndroidActionNotificationIntents.REPLY_TEXT).build()),
                fill,
                Bundle().apply {
                    putCharSequence(
                        AndroidActionNotificationIntents.REPLY_TEXT,
                        NotificationInputProof.TEXT,
                    )
                },
            )
        notification.actions[if (mode == "nonreply") 1 else 0].actionIntent.send(this, 0, fill)
        File(filesDir, "protocol-proof/reply-sent.json")
            .writeText(JSONObject().put("pid", Process.myPid()).put("mode", mode).toString())
        finish()
    }
}
