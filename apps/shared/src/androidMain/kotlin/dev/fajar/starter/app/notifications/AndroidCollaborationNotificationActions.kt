package dev.fajar.starter.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.fajar.fluent.apps.shared.R
import dev.fajar.starter.notifications.data.datasources.AndroidActionNotificationIntents
import dev.fajar.starter.notifications.data.dto.ActionNotificationKind
import java.util.concurrent.atomic.AtomicBoolean

/** The Application owns the facade; no Activity or receiver survives its callback. */
interface AndroidCollaborationActionHost {
    val notificationActions: AndroidCollaborationNotificationActions
}

class AndroidCollaborationNotificationActions(
    context: Context,
    private val intents: AndroidActionNotificationIntents,
    private val replies: AndroidCollaborationNotificationReplies,
    private val callbacks: CollaborationNotificationActionCallbacks,
    private val text: (Int) -> String = { context.applicationContext.getString(it) },
    private val observed: (CollaborationActionCallbackOutcome) -> Unit = {},
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    fun receive(intent: Intent, finished: () -> Unit) {
        val input =
            try {
                intents.input(intent)
            } catch (_: RuntimeException) {
                null
            }
        if (input == null || input.kind == ActionNotificationKind.Open) {
            complete(CollaborationActionCallbackOutcome.NotConfirmed, finished)
        } else if (input.kind == ActionNotificationKind.Reply) {
            replies.receive(intent, finished)
        } else {
            callbacks.receive(input) { complete(it, finished) }
        }
    }

    private fun complete(outcome: CollaborationActionCallbackOutcome, finished: () -> Unit) {
        try {
            if (outcome == CollaborationActionCallbackOutcome.NotConfirmed) {
                main.post {
                    Toast.makeText(
                            context,
                            text(R.string.chat_action_not_confirmed),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            }
            observed(outcome)
        } finally {
            finished()
        }
    }
}

/** Private explicit PendingIntent receiver. Open always uses its Activity PendingIntent. */
class AndroidCollaborationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val done = AtomicBoolean(false)
        val finishOnce = { if (done.compareAndSet(false, true)) pending.finish() }
        try {
            val host = context.applicationContext as? AndroidCollaborationActionHost
            if (host == null) finishOnce() else host.notificationActions.receive(intent, finishOnce)
        } catch (_: RuntimeException) {
            Toast.makeText(
                    context.applicationContext,
                    R.string.chat_action_not_confirmed,
                    Toast.LENGTH_LONG,
                )
                .show()
            finishOnce()
        }
    }
}
