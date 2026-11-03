package dev.fajar.starter.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.fajar.fluent.apps.shared.R
import dev.fajar.starter.collaboration.domain.entities.CollaborationNotificationActionToken
import dev.fajar.starter.notifications.data.datasources.AndroidActionNotificationIntents
import dev.fajar.starter.notifications.data.dto.ActionNotificationKind
import java.util.concurrent.atomic.AtomicBoolean

/** An Application supplies this host; no Activity or receiver is retained by app composition. */
interface AndroidCollaborationNotificationHost {
    val notificationReplies: AndroidCollaborationNotificationReplies
}

class AndroidCollaborationNotificationReplies(
    context: Context,
    private val intents: AndroidActionNotificationIntents,
    private val callbacks: CollaborationNotificationReplyCallbacks,
    private val observed: (CollaborationReplyCaptureOutcome) -> Unit = {},
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun receive(intent: Intent, finished: () -> Unit) {
        val input =
            try {
                intents.input(intent)?.takeIf { it.kind == ActionNotificationKind.Reply }
            } catch (_: RuntimeException) {
                null
            }
        if (input == null) {
            complete(CollaborationReplyCaptureOutcome.NotSaved, finished)
            return
        }
        callbacks.receive(
            CollaborationNotificationActionToken(input.envelope),
            requireNotNull(input.replyText),
        ) {
            complete(it, finished)
        }
    }

    private fun complete(outcome: CollaborationReplyCaptureOutcome, finished: () -> Unit) {
        try {
            main.post {
                val message =
                    if (outcome == CollaborationReplyCaptureOutcome.Saved) R.string.chat_reply_saved
                    else R.string.chat_reply_not_saved
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            observed(outcome)
        } finally {
            finished()
        }
    }
}

/** Private manifest receiver. Open/read/mute/dismiss never become reply submissions here. */
class AndroidCollaborationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val done = AtomicBoolean(false)
        val finishOnce = { if (done.compareAndSet(false, true)) pending.finish() }
        try {
            val host = context.applicationContext as? AndroidCollaborationNotificationHost
            if (host == null) {
                finishOnce()
                return
            }
            host.notificationReplies.receive(intent, finishOnce)
        } catch (_: RuntimeException) {
            // Construction can fail before the asynchronous owner admits the input.
            Toast.makeText(
                    context.applicationContext,
                    R.string.chat_reply_not_saved,
                    Toast.LENGTH_LONG,
                )
                .show()
            finishOnce()
        }
    }
}
