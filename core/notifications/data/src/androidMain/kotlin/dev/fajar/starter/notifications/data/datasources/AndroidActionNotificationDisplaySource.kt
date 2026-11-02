package dev.fajar.starter.notifications.data.datasources

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.notifications.data.mappers.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Raw adapter. A successful Binder submission is not user visibility, reading or delivery. */
class AndroidActionNotificationDisplaySource(
    context: Context,
    private val namespace: String,
    private val iconResource: Int,
    private val intents: AndroidActionNotificationIntents,
    private val labels: () -> ActionNotificationLabels,
) : ActionNotificationDisplaySource {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val channel = "owned-$namespace"
    private val prefix = "owned:$namespace:"

    init {
        require(namespace.matches(Regex("[a-z][a-z0-9_-]{0,47}")))
    }

    override suspend fun show(payload: ActionNotificationPayload): ActionNotificationSubmission {
        currentCoroutineContext().ensureActive()
        require(validActionNotificationPayload(payload))
        if (!allowed()) return ActionNotificationSubmission.PermissionRequired
        val copy = labels()
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channel, copy.channel, NotificationManager.IMPORTANCE_DEFAULT)
            )
            if (
                manager.getNotificationChannel(channel)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
            )
                return ActionNotificationSubmission.ChannelDisabled
        }
        val tag = prefix + payload.reference.id
        val active = manager.activeNotifications
        // Android's per-package limit also includes the application's other notification types.
        if (active.size >= 50 && active.none { it.tag == tag && it.id == 0 })
            return ActionNotificationSubmission.CapacityReached
        val replay =
            active.any {
                it.tag == tag &&
                    it.id == 0 &&
                    it.notification.extras.getString(RENDER) == payload.reference.renderId
            }
        val notification = build(payload, copy, replay)
        currentCoroutineContext().ensureActive()
        manager.notify(tag, 0, notification)
        // Cancellation after notify withholds success; the owner must reconcile/clean exact
        // metadata.
        currentCoroutineContext().ensureActive()
        return ActionNotificationSubmission.Submitted
    }

    override suspend fun active(): List<ActionNotificationReference> {
        currentCoroutineContext().ensureActive()
        val references =
            manager.activeNotifications.mapNotNull { entry ->
                if (entry.id != 0 || entry.tag?.startsWith(prefix) != true) return@mapNotNull null
                val render = entry.notification.extras.getString(RENDER) ?: return@mapNotNull null
                ActionNotificationReference(entry.tag.removePrefix(prefix), render)
                    .takeIf(::validActionNotificationReference)
            }
        currentCoroutineContext().ensureActive()
        return references
    }

    override suspend fun remove(expected: ActionNotificationReference): ActionNotificationRemoval {
        currentCoroutineContext().ensureActive()
        require(validActionNotificationReference(expected))
        val tag = prefix + expected.id
        val current =
            manager.activeNotifications.singleOrNull { it.tag == tag && it.id == 0 }
                ?: return ActionNotificationRemoval.Absent
        if (current.notification.extras.getString(RENDER) != expected.renderId)
            return ActionNotificationRemoval.Replaced
        currentCoroutineContext().ensureActive()
        manager.cancel(tag, 0)
        currentCoroutineContext().ensureActive()
        return ActionNotificationRemoval.Removed
    }

    override suspend fun clear() {
        currentCoroutineContext().ensureActive()
        for (entry in manager.activeNotifications) {
            if (entry.id == 0 && entry.tag?.startsWith(prefix) == true) {
                currentCoroutineContext().ensureActive()
                manager.cancel(entry.tag, entry.id)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun allowed() =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun build(
        payload: ActionNotificationPayload,
        copy: ActionNotificationLabels,
        replay: Boolean,
    ): Notification {
        fun pending(kind: ActionNotificationKind) =
            intents.pending(payload.reference, kind, payload.envelopes.getValue(kind))
        val reply =
            NotificationCompat.Action.Builder(0, copy.reply, pending(ActionNotificationKind.Reply))
                .addRemoteInput(
                    RemoteInput.Builder(AndroidActionNotificationIntents.REPLY_TEXT)
                        .setLabel(copy.reply)
                        .setAllowFreeFormInput(true)
                        .build()
                )
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build()
        val public =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(iconResource)
                .setContentTitle(copy.privateTitle)
                .setContentText(copy.privateBody)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(iconResource)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setContentIntent(pending(ActionNotificationKind.Open))
            .setDeleteIntent(pending(ActionNotificationKind.Dismiss))
            .addAction(reply)
            .addAction(
                NotificationCompat.Action.Builder(
                        0,
                        copy.markRead,
                        pending(ActionNotificationKind.MarkRead),
                    )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                        0,
                        copy.mute,
                        pending(ActionNotificationKind.Mute),
                    )
                    .build()
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAllowSystemGeneratedContextualActions(false)
            .setOnlyAlertOnce(replay)
            .setAutoCancel(false)
            .setSilent(payload.silent)
            .addExtras(Bundle().apply { putString(RENDER, payload.reference.renderId) })
            .build()
    }

    companion object {
        private const val RENDER = "owned_notification_render"
    }
}
