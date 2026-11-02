package dev.fajar.starter.notifications.data.datasources

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.notifications.data.mappers.*

/** Explicit components and full data-URI identity prevent hash collisions or action retargeting. */
class AndroidActionNotificationIntents(
    context: Context,
    private val namespace: String,
    private val activity: ComponentName,
    private val receiver: ComponentName,
) {
    private val context = context.applicationContext

    init {
        require(namespace.matches(Regex("[a-z][a-z0-9_-]{0,47}")))
        require(activity.packageName == this.context.packageName)
        require(receiver.packageName == this.context.packageName)
        @Suppress("DEPRECATION")
        require(!this.context.packageManager.getReceiverInfo(receiver, 0).exported)
    }

    fun pending(
        reference: ActionNotificationReference,
        kind: ActionNotificationKind,
        envelope: String,
    ): PendingIntent {
        require(validActionNotificationReference(reference))
        require(validActionNotificationEnvelope(envelope))
        val intent =
            Intent(ACTION, uri(reference, kind))
                .setComponent(if (kind == ActionNotificationKind.Open) activity else receiver)
                .setPackage(context.packageName)
                .putExtra(ENVELOPE, envelope)
        val flags =
            if (kind == ActionNotificationKind.Reply) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        // A render owns its original envelope. Reissuing the same URI must not rewrite it.
        return if (kind == ActionNotificationKind.Open) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(context, 0, intent, flags)
        } else PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** Size/shape extraction only. The caller derives the parent from its actual session state. */
    fun input(intent: Intent): ActionNotificationInput? {
        if (intent.action != ACTION || intent.`package` != context.packageName) return null
        val data = intent.data ?: return null
        if (
            data.scheme != SCHEME ||
                data.authority != namespace ||
                data.query != null ||
                data.fragment != null
        )
            return null
        val segments = data.pathSegments
        if (segments.size != 3) return null
        val reference = ActionNotificationReference(segments[0], segments[1])
        if (!validActionNotificationReference(reference)) return null
        val kind =
            ActionNotificationKind.entries.singleOrNull { it.key == segments[2] } ?: return null
        if (data != uri(reference, kind)) return null
        if (intent.component != if (kind == ActionNotificationKind.Open) activity else receiver)
            return null
        val envelope = intent.getStringExtra(ENVELOPE) ?: return null
        if (!validActionNotificationEnvelope(envelope)) return null
        val results = RemoteInput.getResultsFromIntent(intent)
        val text = results?.getCharSequence(REPLY_TEXT)
        if (kind == ActionNotificationKind.Reply) {
            if (text == null || text.length !in 1..16_384) return null
        } else if (results != null) return null
        return ActionNotificationInput(reference, kind, envelope, text?.toString())
    }

    private fun uri(reference: ActionNotificationReference, kind: ActionNotificationKind): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(namespace)
            .appendPath(reference.id)
            .appendPath(reference.renderId)
            .appendPath(kind.key)
            .build()

    companion object {
        const val REPLY_TEXT = "private_reply"
        private const val SCHEME = "owned-notification"
        private const val ACTION = "dev.fajar.starter.notification.ACTION"
        private const val ENVELOPE = "private_action_envelope"
    }
}
